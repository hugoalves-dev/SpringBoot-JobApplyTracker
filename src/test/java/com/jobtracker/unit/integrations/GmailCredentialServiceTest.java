package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.entity.integrations.GmailAccount;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.integrations.GmailAccountRepository;
import com.jobtracker.service.integrations.SecretCipher;
import com.jobtracker.service.integrations.gmail.GmailApiClient;
import com.jobtracker.service.integrations.gmail.GmailAuthenticationException;
import com.jobtracker.service.integrations.gmail.GmailCredentialService;
import com.jobtracker.service.integrations.gmail.GmailReauthorizationRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailCredentialServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private final GmailAccountRepository repository = mock(GmailAccountRepository.class);
    private final GmailApiClient apiClient = mock(GmailApiClient.class);
    private final SecretCipher cipher = SecretCipherTest.cipher(SecretCipherTest.KEY);
    private GmailAccount account;
    private GmailCredentialService service;

    @BeforeEach
    void setUp() {
        service = new GmailCredentialService(repository, apiClient, cipher, new IntegrationProperties());
        account = new GmailAccount();
        account.setId(UUID.randomUUID());
        account.setEmail("dev@gmail.com");
        account.setAccessTokenEncrypted(cipher.encrypt("old-access"));
        account.setRefreshTokenEncrypted(cipher.encrypt("refresh"));
        account.setAccessTokenExpiresAt(LocalDateTime.now().plusHours(1));
        when(repository.findByIdAndUserId(account.getId(), USER)).thenAnswer(inv -> Optional.of(account));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void usesFreshTokenWithoutRefreshing() {
        assertThat(service.<String>call(USER, account.getId(), token -> token)).isEqualTo("old-access");
        verify(apiClient, never()).refresh(anyString());
    }

    @Test
    void refreshesExpiredTokenAndStoresItEncrypted() {
        account.setAccessTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(apiClient.refresh("refresh")).thenReturn(new GmailApiClient.OAuthTokens("new-access", "refresh",
                LocalDateTime.now().plusHours(1), null));

        assertThat(service.<String>call(USER, account.getId(), token -> token)).isEqualTo("new-access");
        assertThat(account.getAccessTokenEncrypted()).doesNotContain("new-access");
        assertThat(cipher.decrypt(account.getAccessTokenEncrypted())).isEqualTo("new-access");
    }

    @Test
    void refreshesOnceAndRetriesAfter401() {
        when(apiClient.refresh("refresh")).thenReturn(new GmailApiClient.OAuthTokens("new-access", null,
                LocalDateTime.now().plusHours(1), null));
        List<String> seen = new ArrayList<>();

        String result = service.call(USER, account.getId(), token -> {
            seen.add(token);
            if (token.equals("old-access")) {
                throw new GmailAuthenticationException("401");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(seen).containsExactly("old-access", "new-access");
        verify(apiClient, times(1)).refresh("refresh");
    }

    @Test
    void revokedGrantFlagsAccountForReconnection() {
        account.setAccessTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(apiClient.refresh("refresh")).thenThrow(new GmailReauthorizationRequiredException("revoked"));

        assertThatThrownBy(() -> service.call(USER, account.getId(), token -> token))
                .isInstanceOf(GmailReauthorizationRequiredException.class);
        assertThat(account.isReauthorizationRequired()).isTrue();

        assertThatThrownBy(() -> service.call(USER, account.getId(), token -> token))
                .isInstanceOf(GmailReauthorizationRequiredException.class);
        verify(apiClient, times(1)).refresh("refresh");
    }

    @Test
    void foreignAccountIsNotFound() {
        assertThatThrownBy(() -> service.call(UUID.randomUUID(), account.getId(), token -> token))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
