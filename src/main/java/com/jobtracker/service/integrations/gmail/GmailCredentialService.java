package com.jobtracker.service.integrations.gmail;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.entity.integrations.GmailAccount;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.integrations.GmailAccountRepository;
import com.jobtracker.service.integrations.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Owns the access-token lifecycle of connected Gmail accounts, mirroring
 * {@code GoogleDriveCredentialService}: proactive refresh before expiry, one guarded
 * refresh-and-retry on 401, and a persisted reauthorization flag when the grant is gone.
 * Not transactional on purpose: each save commits on its own, so the flag survives failures.
 */
@Service
public class GmailCredentialService {

    private static final Logger log = LoggerFactory.getLogger(GmailCredentialService.class);

    private final GmailAccountRepository accountRepository;
    private final GmailApiClient apiClient;
    private final SecretCipher cipher;
    private final IntegrationProperties.Gmail properties;
    private final ConcurrentHashMap<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public GmailCredentialService(GmailAccountRepository accountRepository, GmailApiClient apiClient,
                                  SecretCipher cipher, IntegrationProperties properties) {
        this.accountRepository = accountRepository;
        this.apiClient = apiClient;
        this.cipher = cipher;
        this.properties = properties.getGmail();
    }

    public <T> T call(UUID userId, UUID accountId, Function<String, T> operation) {
        GmailAccount account = load(userId, accountId);
        String token = isFresh(account) ? cipher.decrypt(account.getAccessTokenEncrypted()) : refresh(userId, accountId, account);
        try {
            return operation.apply(token);
        } catch (GmailAuthenticationException ex) {
            log.warn("event=GMAIL_ACCESS_TOKEN_REJECTED accountId={} - refreshing and retrying once", accountId);
            String refreshed = refresh(userId, accountId, account);
            try {
                return operation.apply(refreshed);
            } catch (GmailAuthenticationException stillRejected) {
                throw new GmailReauthorizationRequiredException(
                        "Gmail keeps rejecting this account's access. Reconnect it in Settings > Integrations.");
            }
        }
    }

    private String refresh(UUID userId, UUID accountId, GmailAccount stale) {
        synchronized (refreshLocks.computeIfAbsent(accountId, id -> new Object())) {
            GmailAccount current = load(userId, accountId);
            if (!current.getAccessTokenEncrypted().equals(stale.getAccessTokenEncrypted()) && isFresh(current)) {
                return cipher.decrypt(current.getAccessTokenEncrypted());
            }
            GmailApiClient.OAuthTokens tokens;
            try {
                tokens = apiClient.refresh(cipher.decrypt(current.getRefreshTokenEncrypted()));
            } catch (GmailReauthorizationRequiredException ex) {
                current.setReauthorizationRequired(true);
                accountRepository.save(current);
                log.warn("event=GMAIL_REAUTHORIZATION_REQUIRED accountId={}", accountId);
                throw ex;
            }
            current.setAccessTokenEncrypted(cipher.encrypt(tokens.accessToken()));
            current.setAccessTokenExpiresAt(tokens.expiresAt());
            if (tokens.refreshToken() != null) {
                current.setRefreshTokenEncrypted(cipher.encrypt(tokens.refreshToken()));
            }
            if (tokens.scope() != null && !tokens.scope().isBlank()) {
                current.setGrantedScopes(tokens.scope());
            }
            current.setReauthorizationRequired(false);
            accountRepository.save(current);
            return tokens.accessToken();
        }
    }

    private GmailAccount load(UUID userId, UUID accountId) {
        GmailAccount account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Gmail account not found: " + accountId));
        if (account.isReauthorizationRequired()) {
            throw new GmailReauthorizationRequiredException("Gmail account " + account.getEmail()
                    + " needs to be reconnected in Settings > Integrations.");
        }
        return account;
    }

    private boolean isFresh(GmailAccount account) {
        return account.getAccessTokenExpiresAt() != null
                && account.getAccessTokenExpiresAt().isAfter(LocalDateTime.now().plus(properties.getTokenRefreshSkew()));
    }
}
