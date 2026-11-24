package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.dto.integrations.GmailOAuthStartResponse;
import com.jobtracker.entity.User;
import com.jobtracker.entity.integrations.GmailAccount;
import com.jobtracker.entity.integrations.GmailOAuthState;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ConflictException;
import com.jobtracker.repository.integrations.GmailAccountRepository;
import com.jobtracker.repository.integrations.GmailOAuthStateRepository;
import com.jobtracker.service.integrations.SecretCipher;
import com.jobtracker.service.integrations.gmail.GmailAccountService;
import com.jobtracker.service.integrations.gmail.GmailApiClient;
import com.jobtracker.service.integrations.gmail.GmailCredentialService;
import com.jobtracker.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailAccountServiceTest {

    private final GmailAccountRepository accounts = mock(GmailAccountRepository.class);
    private final GmailOAuthStateRepository states = mock(GmailOAuthStateRepository.class);
    private final GmailApiClient apiClient = mock(GmailApiClient.class);
    private final SecurityUtils securityUtils = mock(SecurityUtils.class);
    private final SecretCipher cipher = SecretCipherTest.cipher(SecretCipherTest.KEY);
    private final IntegrationProperties properties = new IntegrationProperties();
    private final User user = new User();
    private GmailAccountService service;

    @BeforeEach
    void setUp() {
        properties.getGmail().setClientId("id");
        properties.getGmail().setClientSecret("secret");
        properties.getGmail().setRedirectUri("https://api.test/cb");
        properties.getGmail().setOauthCompleteUrl("https://app.test/settings/integrations/callback");
        properties.getGmail().setMaxAccountsPerUser(2);
        user.setId(UUID.randomUUID());
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(securityUtils.getCurrentUserId()).thenReturn(user.getId());
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accounts.findByUserIdAndProfileNameIgnoreCase(any(), anyString())).thenReturn(Optional.empty());
        when(accounts.findByUserIdAndEmailIgnoreCase(any(), anyString())).thenReturn(Optional.empty());
        when(apiClient.buildAuthorizationUrl(anyString(), anyString())).thenReturn("https://accounts.google.com/auth");
        service = new GmailAccountService(accounts, states, apiClient, mock(GmailCredentialService.class), cipher,
                securityUtils, properties);
    }

    @Test
    void startStoresStateWithPkceVerifierAndProfile() {
        GmailOAuthStartResponse response = service.startAuthorization("  Java  ");

        ArgumentCaptor<GmailOAuthState> saved = ArgumentCaptor.forClass(GmailOAuthState.class);
        verify(states).save(saved.capture());
        assertThat(saved.getValue().getProfileName()).isEqualTo("Java");
        assertThat(saved.getValue().getCodeVerifier()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(response.scopes()).containsExactly("https://www.googleapis.com/auth/gmail.compose");
        verify(apiClient).buildAuthorizationUrl(eq(saved.getValue().getStateToken()), anyString());
    }

    @Test
    void rejectsInvalidProfileNames() {
        assertThatThrownBy(() -> service.startAuthorization("<script>")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void unknownStateNeverExchangesCode() {
        when(states.findByStateToken("forged")).thenReturn(Optional.empty());

        String redirect = service.handleCallback("forged", "code", null);

        assertThat(decoded(redirect)).contains("status=error").contains("integration=gmail");
        verify(apiClient, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void callbackStoresEncryptedTokensForNewAccount() {
        GmailOAuthState state = state("Java");
        when(apiClient.exchangeCode("code", state.getCodeVerifier()))
                .thenReturn(new GmailApiClient.OAuthTokens("access", "refresh", LocalDateTime.now().plusHours(1), null));
        when(apiClient.getProfileEmail("access")).thenReturn("Dev@Gmail.com");

        String redirect = service.handleCallback("state", "code", null);

        ArgumentCaptor<GmailAccount> saved = ArgumentCaptor.forClass(GmailAccount.class);
        verify(accounts).save(saved.capture());
        GmailAccount account = saved.getValue();
        assertThat(account.getEmail()).isEqualTo("dev@gmail.com");
        assertThat(account.getProfileName()).isEqualTo("Java");
        assertThat(account.getRefreshTokenEncrypted()).doesNotContain("refresh");
        assertThat(cipher.decrypt(account.getRefreshTokenEncrypted())).isEqualTo("refresh");
        assertThat(account.getGrantedScopes()).isEqualTo("https://www.googleapis.com/auth/gmail.compose");
        assertThat(decoded(redirect)).contains("status=success");
        verify(states).delete(state);
    }

    @Test
    void profileAlreadyUsedByAnotherMailboxIsRejectedAndGrantRevoked() {
        state("Java");
        when(apiClient.exchangeCode(anyString(), anyString()))
                .thenReturn(new GmailApiClient.OAuthTokens("access", "refresh", LocalDateTime.now().plusHours(1), null));
        when(apiClient.getProfileEmail("access")).thenReturn("second@gmail.com");
        GmailAccount existing = new GmailAccount();
        existing.setId(UUID.randomUUID());
        existing.setEmail("first@gmail.com");
        when(accounts.findByUserIdAndProfileNameIgnoreCase(user.getId(), "Java")).thenReturn(Optional.of(existing));

        String redirect = service.handleCallback("state", "code", null);

        assertThat(decoded(redirect)).contains("status=error").contains("already used");
        verify(accounts, never()).save(any());
        verify(apiClient).revoke("refresh");
    }

    @Test
    void enforcesMaxAccountsPerUser() {
        state(null);
        when(apiClient.exchangeCode(anyString(), anyString()))
                .thenReturn(new GmailApiClient.OAuthTokens("access", "refresh", LocalDateTime.now().plusHours(1), null));
        when(apiClient.getProfileEmail("access")).thenReturn("third@gmail.com");
        when(accounts.countByUserId(user.getId())).thenReturn(2L);

        assertThat(decoded(service.handleCallback("state", "code", null))).contains("at most 2");
        verify(accounts, never()).save(any());
    }

    @Test
    void renameRejectsDuplicateProfile() {
        GmailAccount mine = new GmailAccount();
        mine.setId(UUID.randomUUID());
        GmailAccount other = new GmailAccount();
        other.setId(UUID.randomUUID());
        other.setEmail("other@gmail.com");
        when(accounts.findByIdAndUserId(mine.getId(), user.getId())).thenReturn(Optional.of(mine));
        when(accounts.findByUserIdAndProfileNameIgnoreCase(user.getId(), "CSharp")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.rename(mine.getId(), "CSharp")).isInstanceOf(ConflictException.class);
    }

    @Test
    void disconnectRevokesAndDeletesEvenIfRevocationFails() {
        GmailAccount mine = new GmailAccount();
        mine.setId(UUID.randomUUID());
        mine.setRefreshTokenEncrypted(cipher.encrypt("refresh"));
        when(accounts.findByIdAndUserId(mine.getId(), user.getId())).thenReturn(Optional.of(mine));
        when(apiClient.revoke("refresh")).thenReturn(false);

        service.disconnect(mine.getId());

        verify(apiClient).revoke("refresh");
        verify(accounts).delete(mine);
    }

    private GmailOAuthState state(String profile) {
        GmailOAuthState state = new GmailOAuthState();
        state.setUser(user);
        state.setStateToken("state");
        state.setCodeVerifier("verifier-verifier-verifier-verifier-verifier-1");
        state.setProfileName(profile);
        state.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(states.findByStateToken("state")).thenReturn(Optional.of(state));
        return state;
    }

    private static String decoded(String url) {
        return URLDecoder.decode(url, StandardCharsets.UTF_8);
    }
}
