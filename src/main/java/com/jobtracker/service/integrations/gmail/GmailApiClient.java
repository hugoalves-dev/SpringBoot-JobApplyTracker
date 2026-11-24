package com.jobtracker.service.integrations.gmail;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Minimal Gmail REST client: OAuth 2.0 (authorization code + PKCE, refresh, revoke),
 * {@code users.getProfile} and {@code users.drafts.create}. Deliberately has no send operation.
 */
@Component
public class GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);

    private final IntegrationProperties.Gmail properties;
    private final RestClient restClient;

    @Autowired
    public GmailApiClient(IntegrationProperties properties, RestClient.Builder builder) {
        this(properties, builder.requestFactory(requestFactory(properties.getGmail())).build());
    }

    public GmailApiClient(IntegrationProperties properties, RestClient restClient) {
        this.properties = properties.getGmail();
        this.restClient = restClient;
    }

    public String buildAuthorizationUrl(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.getAuthorizationUri())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", properties.getScopes()))
                .queryParam("access_type", "offline")
                // Without prompt=consent Google omits the refresh token on re-authorization.
                .queryParam("prompt", "consent select_account")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
    }

    public OAuthTokens exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri());
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("code_verifier", codeVerifier);
        TokenResponse response = tokenRequest(form, "exchange authorization code");
        if (response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BadRequestException("Google did not return a refresh token. Reconnect and grant consent again.");
        }
        return response.toTokens(null);
    }

    public OAuthTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        return tokenRequest(form, "refresh access token").toTokens(refreshToken);
    }

    /** Best effort: a failed revocation must not block local disconnection. */
    public boolean revoke(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            return restClient.post().uri(properties.getRevokeUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
        } catch (RestClientException ex) {
            log.warn("event=GMAIL_REVOKE_FAILED reason={}", ex.getClass().getSimpleName());
            return false;
        }
    }

    public String getProfileEmail(String accessToken) {
        ApiResponse response = apiCall(() -> restClient.get()
                .uri(properties.getApiBaseUrl() + "/users/me/profile")
                .header("Authorization", "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, raw) -> toApiResponse(raw)));
        ensureSuccess(response, "read Gmail profile");
        Object email = response.body().get("emailAddress");
        if (!(email instanceof String value) || value.isBlank()) {
            throw new ServiceUnavailableException("Gmail did not return the account email address");
        }
        return value;
    }

    /**
     * Calls {@code users.drafts.create} with a base64url-encoded RFC 2822 message. Network errors
     * and 5xx are reported as {@link DraftOutcomeUnknownException}: Gmail may or may not have
     * created the draft, so callers must not retry automatically.
     */
    public CreatedDraft createDraft(String accessToken, String rawBase64Url) {
        ApiResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getApiBaseUrl() + "/users/me/drafts")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", Map.of("raw", rawBase64Url)))
                    .exchange((request, raw) -> toApiResponse(raw));
        } catch (ResourceAccessException ex) {
            throw new DraftOutcomeUnknownException("Gmail did not answer in time; the draft may or may not exist");
        } catch (RestClientException ex) {
            throw new DraftOutcomeUnknownException("Gmail request failed mid-flight; the draft may or may not exist");
        }
        if (response.status() >= 500) {
            throw new DraftOutcomeUnknownException("Gmail returned HTTP " + response.status() + "; the draft may or may not exist");
        }
        ensureSuccess(response, "create Gmail draft");
        Object id = response.body().get("id");
        String messageId = null;
        if (response.body().get("message") instanceof Map<?, ?> message && message.get("id") != null) {
            messageId = message.get("id").toString();
        }
        if (id == null) {
            throw new DraftOutcomeUnknownException("Gmail accepted the request but returned no draft id");
        }
        return new CreatedDraft(id.toString(), messageId);
    }

    private TokenResponse tokenRequest(MultiValueMap<String, String> form, String operation) {
        ApiResponse response = apiCall(() -> restClient.post()
                .uri(properties.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .exchange((request, raw) -> toApiResponse(raw)));
        if (response.status() == 400 || response.status() == 401) {
            Object error = response.body() == null ? null : response.body().get("error");
            if ("invalid_grant".equals(error) || "invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                throw new GmailReauthorizationRequiredException(
                        "Gmail authorization was revoked or expired. Reconnect the account in Settings > Integrations.");
            }
        }
        ensureSuccess(response, operation);
        Map<String, Object> body = response.body();
        Object accessToken = body.get("access_token");
        if (accessToken == null) {
            throw new ServiceUnavailableException("Google token endpoint returned no access token");
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
        return new TokenResponse(accessToken.toString(),
                body.get("refresh_token") == null ? null : body.get("refresh_token").toString(),
                expiresIn,
                body.get("scope") == null ? null : body.get("scope").toString());
    }

    private void ensureSuccess(ApiResponse response, String operation) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401) {
            throw new GmailAuthenticationException("Gmail rejected the access token");
        }
        if (status == 403) {
            throw new BadRequestException("Gmail refused to " + operation
                    + " (missing gmail.compose permission or API disabled). Reconnect the account and grant access.");
        }
        if (status == 429 || status >= 500) {
            throw new ServiceUnavailableException("Gmail is temporarily unavailable (HTTP " + status + "). Retry shortly.");
        }
        throw new BadRequestException("Gmail could not " + operation + " (HTTP " + status + ")" + errorDetail(response));
    }

    private static String errorDetail(ApiResponse response) {
        if (response.body() != null && response.body().get("error") instanceof Map<?, ?> error
                && error.get("message") instanceof String message) {
            return ": " + message;
        }
        return "";
    }

    private ApiResponse apiCall(java.util.function.Supplier<ApiResponse> call) {
        try {
            return call.get();
        } catch (RestClientException ex) {
            throw new ServiceUnavailableException("Google APIs are unreachable right now. Retry shortly.");
        }
    }

    @SuppressWarnings("unchecked")
    private static ApiResponse toApiResponse(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse raw)
            throws java.io.IOException {
        int status = raw.getStatusCode().value();
        Map<String, Object> body;
        try {
            body = raw.bodyTo(Map.class);
        } catch (RestClientException ex) {
            body = null;
        }
        return new ApiResponse(status, body == null ? Map.of() : body);
    }

    private static SimpleClientHttpRequestFactory requestFactory(IntegrationProperties.Gmail properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    private record ApiResponse(int status, Map<String, Object> body) {}

    private record TokenResponse(String accessToken, String refreshToken, long expiresIn, String scope) {
        OAuthTokens toTokens(String fallbackRefreshToken) {
            String refresh = refreshToken == null || refreshToken.isBlank() ? fallbackRefreshToken : refreshToken;
            return new OAuthTokens(accessToken, refresh, LocalDateTime.now().plusSeconds(expiresIn), scope);
        }
    }

    public record OAuthTokens(String accessToken, String refreshToken, LocalDateTime expiresAt, String scope) {}

    public record CreatedDraft(String draftId, String messageId) {}
}
