package com.jobtracker.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 8414 path-aware Authorization Server Metadata.
 *
 * Spring Authorization Server only serves /.well-known/oauth-authorization-server at the
 * root (no path suffix). ChatGPT follows the path-aware variant from RFC 8414 §3.1 and
 * appends the protected-resource path suffix, so it requests:
 *   GET /.well-known/oauth-authorization-server/mcp
 * which the built-in endpoint never matches. This controller fills that gap and also
 * ensures "none" appears in token_endpoint_auth_methods_supported so public PKCE clients
 * are not rejected before the flow starts.
 */
@RestController
public class OAuthAuthorizationServerMetadataController {

    private static final String WELL_KNOWN_PREFIX = "/.well-known/oauth-authorization-server";

    private final AuthorizationServerSettings settings;
    private final McpOAuthProperties mcpOAuthProperties;

    public OAuthAuthorizationServerMetadataController(
            AuthorizationServerSettings settings,
            McpOAuthProperties mcpOAuthProperties) {
        this.settings = settings;
        this.mcpOAuthProperties = mcpOAuthProperties;
    }

    @GetMapping(
            value = {WELL_KNOWN_PREFIX, WELL_KNOWN_PREFIX + "/**"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorizationServerMetadata(HttpServletRequest request) {
        String issuer = settings.getIssuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("OAuth issuer must be configured for discovery metadata");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer);
        metadata.put("authorization_endpoint", issuer + settings.getAuthorizationEndpoint());
        metadata.put("token_endpoint", issuer + settings.getTokenEndpoint());
        metadata.put("jwks_uri", issuer + settings.getJwkSetEndpoint());
        if (mcpOAuthProperties.isDcrEnabled()) {
            metadata.put("registration_endpoint", issuer + registrationPath());
        }
        metadata.put("scopes_supported", mcpOAuthProperties.getScopes());
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        metadata.put("token_endpoint_auth_methods_supported", List.of(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(),
                ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue(),
                ClientAuthenticationMethod.NONE.getValue()
        ));
        metadata.put("revocation_endpoint", issuer + settings.getTokenRevocationEndpoint());
        metadata.put("revocation_endpoint_auth_methods_supported", List.of(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(),
                ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue(),
                ClientAuthenticationMethod.NONE.getValue()
        ));
        metadata.put("introspection_endpoint", issuer + settings.getTokenIntrospectionEndpoint());
        metadata.put("code_challenge_methods_supported", List.of("S256"));

        return metadata;
    }

    private String registrationPath() {
        String path = settings.getOidcClientRegistrationEndpoint();
        return (!path.isBlank()) ? path : "/connect/register";
    }
}
