package com.jobtracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Per-user external integrations (TinyFish Fetch, Gmail drafts). Secrets supplied by users are
 * encrypted with {@link #encryptionKey} before they are persisted.
 */
@Component
@ConfigurationProperties(prefix = "app.integrations")
public class IntegrationProperties {

    /** Base64-encoded 256-bit AES key. Without it, integrations that store secrets are unavailable. */
    private String encryptionKey;
    private final TinyFetch tinyfetch = new TinyFetch();
    private final Gmail gmail = new Gmail();

    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public TinyFetch getTinyfetch() { return tinyfetch; }
    public Gmail getGmail() { return gmail; }

    public static class TinyFetch {
        private String baseUrl = "https://api.fetch.tinyfish.ai";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(45);
        private int maxContentChars = 60_000;
        private Duration cacheTtl = Duration.ofMinutes(15);
        private int cacheMaxEntries = 500;
        private int maxAttempts = 3;
        private Duration retryBaseDelay = Duration.ofSeconds(1);
        private Duration maxRetryDelay = Duration.ofSeconds(10);
        /** Upper bounds from the TinyFish free tier; users may only configure lower values. */
        private int maxPerMinuteLimit = 150;
        private int maxDailyLimit = 1000;
        private String testUrl = "https://example.com/";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public int getMaxContentChars() { return maxContentChars; }
        public void setMaxContentChars(int maxContentChars) { this.maxContentChars = maxContentChars; }
        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
        public int getCacheMaxEntries() { return cacheMaxEntries; }
        public void setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getRetryBaseDelay() { return retryBaseDelay; }
        public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
        public Duration getMaxRetryDelay() { return maxRetryDelay; }
        public void setMaxRetryDelay(Duration maxRetryDelay) { this.maxRetryDelay = maxRetryDelay; }
        public int getMaxPerMinuteLimit() { return maxPerMinuteLimit; }
        public void setMaxPerMinuteLimit(int maxPerMinuteLimit) { this.maxPerMinuteLimit = maxPerMinuteLimit; }
        public int getMaxDailyLimit() { return maxDailyLimit; }
        public void setMaxDailyLimit(int maxDailyLimit) { this.maxDailyLimit = maxDailyLimit; }
        public String getTestUrl() { return testUrl; }
        public void setTestUrl(String testUrl) { this.testUrl = testUrl; }
    }

    public static class Gmail {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String oauthCompleteUrl;
        private String authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth";
        private String tokenUri = "https://oauth2.googleapis.com/token";
        private String revokeUri = "https://oauth2.googleapis.com/revoke";
        private String apiBaseUrl = "https://gmail.googleapis.com/gmail/v1";
        /** gmail.compose is the narrowest scope that allows drafts.create; it also covers users.getProfile. */
        private List<String> scopes = List.of("https://www.googleapis.com/auth/gmail.compose");
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);
        private Duration tokenRefreshSkew = Duration.ofSeconds(60);
        private int maxAccountsPerUser = 5;
        private int maxHtmlChars = 200_000;
        /** Gmail caps a whole message at 25 MB after encoding; keep the raw attachment well under it. */
        private int maxAttachmentBytes = 10 * 1024 * 1024;
        /** A draft request stuck in PENDING longer than this is treated as ambiguous, never retried blindly. */
        private Duration pendingTimeout = Duration.ofMinutes(2);

        public boolean isConfigured() {
            return hasText(clientId) && hasText(clientSecret) && hasText(redirectUri) && hasText(oauthCompleteUrl);
        }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
        public String getOauthCompleteUrl() { return oauthCompleteUrl; }
        public void setOauthCompleteUrl(String oauthCompleteUrl) { this.oauthCompleteUrl = oauthCompleteUrl; }
        public String getAuthorizationUri() { return authorizationUri; }
        public void setAuthorizationUri(String authorizationUri) { this.authorizationUri = authorizationUri; }
        public String getTokenUri() { return tokenUri; }
        public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
        public String getRevokeUri() { return revokeUri; }
        public void setRevokeUri(String revokeUri) { this.revokeUri = revokeUri; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public Duration getTokenRefreshSkew() { return tokenRefreshSkew; }
        public void setTokenRefreshSkew(Duration tokenRefreshSkew) { this.tokenRefreshSkew = tokenRefreshSkew; }
        public int getMaxAccountsPerUser() { return maxAccountsPerUser; }
        public void setMaxAccountsPerUser(int maxAccountsPerUser) { this.maxAccountsPerUser = maxAccountsPerUser; }
        public int getMaxHtmlChars() { return maxHtmlChars; }
        public void setMaxHtmlChars(int maxHtmlChars) { this.maxHtmlChars = maxHtmlChars; }
        public int getMaxAttachmentBytes() { return maxAttachmentBytes; }
        public void setMaxAttachmentBytes(int maxAttachmentBytes) { this.maxAttachmentBytes = maxAttachmentBytes; }
        public Duration getPendingTimeout() { return pendingTimeout; }
        public void setPendingTimeout(Duration pendingTimeout) { this.pendingTimeout = pendingTimeout; }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
