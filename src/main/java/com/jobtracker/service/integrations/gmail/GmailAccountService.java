package com.jobtracker.service.integrations.gmail;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.dto.integrations.GmailAccountResponse;
import com.jobtracker.dto.integrations.GmailIntegrationStatusResponse;
import com.jobtracker.dto.integrations.GmailOAuthStartResponse;
import com.jobtracker.dto.integrations.IntegrationTestResponse;
import com.jobtracker.entity.User;
import com.jobtracker.entity.integrations.GmailAccount;
import com.jobtracker.entity.integrations.GmailOAuthState;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ConflictException;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.integrations.GmailAccountRepository;
import com.jobtracker.repository.integrations.GmailOAuthStateRepository;
import com.jobtracker.service.integrations.SecretCipher;
import com.jobtracker.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Connect, list, rename, test and disconnect a user's Gmail accounts (OAuth 2.0 + PKCE). */
@Service
public class GmailAccountService {

    private static final Logger log = LoggerFactory.getLogger(GmailAccountService.class);
    private static final int STATE_TTL_MINUTES = 10;
    private static final Pattern PROFILE_NAME = Pattern.compile("[\\p{L}\\p{N} ._#+-]{1,60}");

    private final GmailAccountRepository accountRepository;
    private final GmailOAuthStateRepository stateRepository;
    private final GmailApiClient apiClient;
    private final GmailCredentialService credentialService;
    private final SecretCipher cipher;
    private final SecurityUtils securityUtils;
    private final IntegrationProperties.Gmail properties;
    private final SecureRandom random = new SecureRandom();

    public GmailAccountService(GmailAccountRepository accountRepository,
                               GmailOAuthStateRepository stateRepository,
                               GmailApiClient apiClient,
                               GmailCredentialService credentialService,
                               SecretCipher cipher,
                               SecurityUtils securityUtils,
                               IntegrationProperties properties) {
        this.accountRepository = accountRepository;
        this.stateRepository = stateRepository;
        this.apiClient = apiClient;
        this.credentialService = credentialService;
        this.cipher = cipher;
        this.securityUtils = securityUtils;
        this.properties = properties.getGmail();
    }

    @Transactional(readOnly = true)
    public GmailIntegrationStatusResponse getStatus() {
        return new GmailIntegrationStatusResponse(isServerConfigured(), properties.getMaxAccountsPerUser(),
                properties.getScopes(), listAccounts());
    }

    @Transactional(readOnly = true)
    public List<GmailAccountResponse> listAccounts() {
        UUID userId = securityUtils.getCurrentUserId();
        return accountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(GmailAccountService::toResponse).toList();
    }

    @Transactional
    public GmailOAuthStartResponse startAuthorization(String requestedProfileName) {
        requireServerConfigured();
        User user = securityUtils.getCurrentUser();
        String profileName = normalizeProfileName(requestedProfileName, true);
        stateRepository.deleteByExpiresAtBefore(LocalDateTime.now());

        String verifier = randomToken(64);
        GmailOAuthState state = new GmailOAuthState();
        state.setUser(user);
        state.setStateToken(randomToken(32));
        state.setCodeVerifier(verifier);
        state.setProfileName(profileName);
        state.setExpiresAt(LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES));
        stateRepository.save(state);

        return new GmailOAuthStartResponse(
                apiClient.buildAuthorizationUrl(state.getStateToken(), challengeFor(verifier)),
                properties.getScopes(),
                state.getExpiresAt());
    }

    @Transactional
    public String handleCallback(String stateToken, String code, String error) {
        if (!isServerConfigured()) {
            return redirect("error", "Gmail integration is not configured on the server");
        }
        GmailOAuthState state = stateToken == null || stateToken.isBlank()
                ? null : stateRepository.findByStateToken(stateToken).orElse(null);
        try {
            if (state == null) {
                return redirect("error", "Invalid or expired Gmail authorization request. Start again.");
            }
            if (state.getExpiresAt().isBefore(LocalDateTime.now())) {
                return redirect("error", "Gmail authorization request expired. Start again.");
            }
            if (error != null && !error.isBlank()) {
                return redirect("error", "Google returned an OAuth error: " + error);
            }
            if (code == null || code.isBlank()) {
                return redirect("error", "Missing authorization code");
            }

            GmailApiClient.OAuthTokens tokens = apiClient.exchangeCode(code, state.getCodeVerifier());
            String email = apiClient.getProfileEmail(tokens.accessToken()).toLowerCase(Locale.ROOT);
            User user = state.getUser();

            GmailAccount account = accountRepository.findByUserIdAndEmailIgnoreCase(user.getId(), email).orElse(null);
            boolean isNew = account == null;
            if (isNew) {
                if (accountRepository.countByUserId(user.getId()) >= properties.getMaxAccountsPerUser()) {
                    apiClient.revoke(tokens.refreshToken());
                    return redirect("error", "You can connect at most " + properties.getMaxAccountsPerUser() + " Gmail accounts");
                }
                account = new GmailAccount();
                account.setUser(user);
                account.setEmail(email);
            }
            String profileName = state.getProfileName() != null ? state.getProfileName()
                    : (isNew ? uniqueDefaultProfileName(user.getId(), email) : account.getProfileName());
            GmailAccount sameProfile = accountRepository.findByUserIdAndProfileNameIgnoreCase(user.getId(), profileName).orElse(null);
            if (sameProfile != null && !sameProfile.getEmail().equalsIgnoreCase(email)) {
                if (isNew) {
                    apiClient.revoke(tokens.refreshToken());
                }
                return redirect("error", "Profile \"" + profileName + "\" is already used by " + sameProfile.getEmail());
            }

            account.setProfileName(profileName);
            account.setAccessTokenEncrypted(cipher.encrypt(tokens.accessToken()));
            account.setRefreshTokenEncrypted(cipher.encrypt(tokens.refreshToken()));
            account.setAccessTokenExpiresAt(tokens.expiresAt());
            account.setGrantedScopes(tokens.scope() == null || tokens.scope().isBlank()
                    ? String.join(" ", properties.getScopes()) : tokens.scope());
            account.setReauthorizationRequired(false);
            account.setConnectedAt(LocalDateTime.now());
            accountRepository.save(account);
            log.info("event=GMAIL_ACCOUNT_CONNECTED userId={} accountNew={}", user.getId(), isNew);
            return redirect("success", "Gmail account " + email + " connected as \"" + profileName + "\"");
        } catch (BadRequestException ex) {
            return redirect("error", ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("event=GMAIL_OAUTH_CALLBACK_ERROR type={}", ex.getClass().getSimpleName());
            return redirect("error", "Could not connect Gmail. Please try again.");
        } finally {
            if (state != null) {
                stateRepository.delete(state);
            }
        }
    }

    @Transactional
    public GmailAccountResponse rename(UUID accountId, String requestedProfileName) {
        UUID userId = securityUtils.getCurrentUserId();
        GmailAccount account = owned(userId, accountId);
        String profileName = normalizeProfileName(requestedProfileName, false);
        accountRepository.findByUserIdAndProfileNameIgnoreCase(userId, profileName)
                .filter(other -> !other.getId().equals(account.getId()))
                .ifPresent(other -> {
                    throw new ConflictException("Profile \"" + profileName + "\" is already used by " + other.getEmail());
                });
        account.setProfileName(profileName);
        return toResponse(accountRepository.save(account));
    }

    public IntegrationTestResponse test(UUID accountId) {
        UUID userId = securityUtils.getCurrentUserId();
        GmailAccount account = owned(userId, accountId);
        long started = System.nanoTime();
        String email = credentialService.call(userId, accountId, apiClient::getProfileEmail);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        GmailAccount refreshed = owned(userId, accountId);
        refreshed.setLastTestedAt(LocalDateTime.now());
        accountRepository.save(refreshed);
        boolean matches = email.equalsIgnoreCase(account.getEmail());
        return new IntegrationTestResponse(matches,
                matches ? "Gmail access is working for " + email
                        : "Token belongs to " + email + " instead of " + account.getEmail() + "; reconnect the account",
                latencyMs);
    }

    @Transactional
    public void disconnect(UUID accountId) {
        UUID userId = securityUtils.getCurrentUserId();
        GmailAccount account = owned(userId, accountId);
        boolean revoked = false;
        try {
            revoked = apiClient.revoke(cipher.decrypt(account.getRefreshTokenEncrypted()));
        } catch (RuntimeException ex) {
            log.warn("event=GMAIL_REVOKE_SKIPPED accountId={} type={}", accountId, ex.getClass().getSimpleName());
        }
        accountRepository.delete(account);
        log.info("event=GMAIL_ACCOUNT_DISCONNECTED userId={} accountId={} revoked={}", userId, accountId, revoked);
    }

    public boolean isServerConfigured() {
        return properties.isConfigured() && cipher.isConfigured();
    }

    private GmailAccount owned(UUID userId, UUID accountId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Gmail account not found: " + accountId));
    }

    private void requireServerConfigured() {
        if (!properties.isConfigured()) {
            throw new BadRequestException("Gmail integration is not configured on the server");
        }
        if (!cipher.isConfigured()) {
            throw new BadRequestException("Integration encryption key is not configured on the server");
        }
    }

    static String normalizeProfileName(String value, boolean optional) {
        if (value == null || value.isBlank()) {
            if (optional) {
                return null;
            }
            throw new BadRequestException("profileName is required");
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (!PROFILE_NAME.matcher(trimmed).matches()) {
            throw new BadRequestException("profileName may contain letters, digits, spaces and . _ # + - (max 60)");
        }
        return trimmed;
    }

    private String uniqueDefaultProfileName(UUID userId, String email) {
        String base = email.substring(0, Math.max(1, email.indexOf('@'))).replaceAll("[^\\p{L}\\p{N}._+-]", "");
        if (base.isBlank()) {
            base = "Gmail";
        }
        base = base.length() > 50 ? base.substring(0, 50) : base;
        String candidate = base;
        for (int i = 2; accountRepository.findByUserIdAndProfileNameIgnoreCase(userId, candidate).isPresent(); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private String redirect(String status, String message) {
        return UriComponentsBuilder.fromUriString(properties.getOauthCompleteUrl())
                .queryParam("integration", "gmail")
                .queryParam("status", status)
                .queryParam("message", message)
                .build()
                .encode()
                .toUriString();
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static GmailAccountResponse toResponse(GmailAccount account) {
        return new GmailAccountResponse(account.getId(), account.getEmail(), account.getProfileName(),
                account.getConnectedAt(), account.getLastTestedAt(), account.isReauthorizationRequired());
    }
}
