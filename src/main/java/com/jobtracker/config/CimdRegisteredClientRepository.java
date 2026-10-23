package com.jobtracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RegisteredClientRepository} that adds support for CIMD (Client ID Metadata Documents,
 * draft-ietf-oauth-client-id-metadata-document) on top of the persistent JDBC repository.
 *
 * <p>With CIMD, a client (e.g. ChatGPT) does not register ahead of time. Instead it presents the
 * URL of its metadata document as the {@code client_id} — for example
 * {@code https://chatgpt.com/oauth/.../client.json}. When the authorization server looks up such a
 * client, this repository fetches that document over HTTPS, validates it, and builds an
 * <em>ephemeral</em> {@link RegisteredClient} (never persisted) so the authorization-code flow can
 * proceed.
 *
 * <p>All other (non-URL) client IDs — the bootstrapped GPT Actions / MCP clients and any
 * DCR-registered clients — are delegated unchanged to the wrapped JDBC repository.
 *
 * <p>SSRF protection: a CIMD {@code client_id} URL must use HTTPS and must not resolve to a
 * loopback, link-local, site-local/private, or any-local address. Fetch/parse failures are
 * swallowed and surfaced as a {@code null} client so Spring Security returns a standard OAuth
 * error rather than a 500.
 */
public class CimdRegisteredClientRepository implements RegisteredClientRepository {

    private static final Logger log = LoggerFactory.getLogger(CimdRegisteredClientRepository.class);

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final RegisteredClientRepository delegate;
    private final McpOAuthProperties mcpOAuthProperties;
    private final RestClient restClient;

    // Ephemeral CIMD clients keyed by their generated id (SHA-256 of the client_id URL). The token
    // endpoint reloads a client via findById() after the authorization step, but ephemeral clients
    // are not persisted — caching them here keeps a single CIMD authorization-code flow working
    // end to end without a database round-trip.
    private final ConcurrentHashMap<String, RegisteredClient> ephemeralClients = new ConcurrentHashMap<>();

    public CimdRegisteredClientRepository(
            RegisteredClientRepository delegate,
            McpOAuthProperties mcpOAuthProperties,
            RestClient cimdRestClient) {
        this.delegate = delegate;
        this.mcpOAuthProperties = mcpOAuthProperties;
        this.restClient = cimdRestClient;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // Ephemeral CIMD clients are never saved; everything else persists via JDBC.
        delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        RegisteredClient ephemeral = ephemeralClients.get(id);
        if (ephemeral != null) {
            return ephemeral;
        }
        return delegate.findById(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (clientId.startsWith("https://")) {
            return resolveCimdClient(clientId);
        }
        return delegate.findByClientId(clientId);
    }

    private RegisteredClient resolveCimdClient(String clientIdUrl) {
        if (!isSafeCimdUrl(clientIdUrl)) {
            log.warn("event=CIMD_REJECTED reason=unsafe_url client_id={}", clientIdUrl);
            return null;
        }

        Map<String, Object> document = fetchCimdDocument(clientIdUrl);
        if (document == null) {
            return null;
        }

        List<String> redirectUris = stringList(document.get("redirect_uris"));
        if (redirectUris.isEmpty()) {
            log.warn("event=CIMD_REJECTED reason=missing_redirect_uris client_id={}", clientIdUrl);
            return null;
        }

        Set<String> scopes = resolveScopes(document.get("scope"));
        Set<AuthorizationGrantType> grantTypes = resolveGrantTypes(document.get("grant_types"));

        RegisteredClient client = RegisteredClient.withId(sha256(clientIdUrl))
                .clientId(clientIdUrl)
                .clientName(clientName(document, clientIdUrl))
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantTypes(set -> set.addAll(grantTypes))
                .redirectUris(set -> set.addAll(redirectUris))
                .scopes(set -> set.addAll(scopes))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)            // PKCE is mandatory per the MCP spec
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                        .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        ephemeralClients.put(client.getId(), client);
        return client;
    }

    private Map<String, Object> fetchCimdDocument(String url) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.isEmpty()) {
                log.warn("event=CIMD_FETCH_FAILED reason=empty_document client_id={}", url);
                return null;
            }
            return body;
        } catch (Exception ex) {
            log.warn("event=CIMD_FETCH_FAILED client_id={} message={}", url, ex.getMessage());
            return null;
        }
    }

    /**
     * SSRF guard: only HTTPS URLs whose host resolves exclusively to public addresses are allowed.
     */
    private boolean isSafeCimdUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()
                        || address.isAnyLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            log.warn("event=CIMD_URL_VALIDATION_FAILED client_id={} message={}", url, ex.getMessage());
            return false;
        }
    }

    private Set<String> resolveScopes(Object rawScope) {
        List<String> allowed = mcpOAuthProperties.getScopes();
        if (allowed.isEmpty()) {
            allowed = List.of("openid", "read:profile", "read:applications", "write:applications",
                    "read:resume", "read:google-drive", "read:metrics");
        }
        Set<String> result = new LinkedHashSet<>();
        if (rawScope instanceof String scopeStr && !scopeStr.isBlank()) {
            Set<String> requested = Set.of(scopeStr.trim().split("\\s+"));
            for (String scope : allowed) {
                if (requested.contains(scope)) {
                    result.add(scope);
                }
            }
        }
        if (result.isEmpty()) {
            result.addAll(allowed);
        }
        return result;
    }

    private Set<AuthorizationGrantType> resolveGrantTypes(Object rawGrantTypes) {
        Set<AuthorizationGrantType> grantTypes = new LinkedHashSet<>();
        for (String grant : stringList(rawGrantTypes)) {
            if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(grant)) {
                grantTypes.add(AuthorizationGrantType.AUTHORIZATION_CODE);
            } else if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grant)) {
                grantTypes.add(AuthorizationGrantType.REFRESH_TOKEN);
            }
        }
        // The MCP authorization-code flow always needs these two; default when the doc omits them.
        grantTypes.add(AuthorizationGrantType.AUTHORIZATION_CODE);
        grantTypes.add(AuthorizationGrantType.REFRESH_TOKEN);
        return grantTypes;
    }

    private String clientName(Map<String, Object> document, String fallback) {
        Object name = document.get("client_name");
        if (name instanceof String s && !s.isBlank()) {
            String trimmed = s.trim();
            return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
        }
        return fallback;
    }

    private static List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS; this is unreachable on any supported JVM.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
