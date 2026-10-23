package com.jobtracker.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata.
 *
 * Claude's MCP client fetches this before starting OAuth to discover which
 * authorization server protects the resource and which scopes are required.
 * Spring AI MCP Server WebMVC 1.0.0 does not auto-register this endpoint.
 *
 * Path-specific variant (RFC 9728 §4): when the client requests
 *   GET /.well-known/oauth-protected-resource/mcp/messages
 * the suffix (/mcp/messages) identifies the specific resource being accessed,
 * so we reflect it back in the "resource" field.
 */
@RestController
public class OAuthProtectedResourceMetadataController {

    private static final String WELL_KNOWN_PREFIX = "/.well-known/oauth-protected-resource";

    private final AuthorizationServerSettings authorizationServerSettings;
    private final McpOAuthProperties mcpOAuthProperties;

    public OAuthProtectedResourceMetadataController(
            AuthorizationServerSettings authorizationServerSettings,
            McpOAuthProperties mcpOAuthProperties) {
        this.authorizationServerSettings = authorizationServerSettings;
        this.mcpOAuthProperties = mcpOAuthProperties;
    }

    @GetMapping(
            value = {WELL_KNOWN_PREFIX, WELL_KNOWN_PREFIX + "/**"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata(HttpServletRequest request) {
        String issuer = authorizationServerSettings.getIssuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("OAuth issuer must be configured for discovery metadata");
        }
        String requestPath = request.getRequestURI();

        // Derive the resource path suffix after the well-known prefix.
        // "/.well-known/oauth-protected-resource"           → suffix = ""   → resource = issuer
        // "/.well-known/oauth-protected-resource/mcp/messages" → suffix = "/mcp/messages"
        String suffix = requestPath.startsWith(WELL_KNOWN_PREFIX)
                ? requestPath.substring(WELL_KNOWN_PREFIX.length())
                : "";
        String resource = issuer + suffix;

        // LinkedHashMap (not Map.of) so the JSON field order is stable and readable.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", resource);
        metadata.put("authorization_servers", List.of(issuer));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("scopes_supported", mcpOAuthProperties.getScopes());

        // Advertise the Dynamic Client Registration endpoint (RFC 7591). ChatGPT reads the
        // protected-resource metadata first and only enables DCR when it finds a
        // "registration_endpoint" here — it does not fall through to /.well-known/openid-configuration.
        // Never advertise it while the endpoint is disabled: clients that find a
        // registration_endpoint returning 403 abort with a generic connection error.
        if (mcpOAuthProperties.isDcrEnabled()) {
            metadata.put("registration_endpoint", registrationEndpoint(issuer));
        }

        // Advertise CIMD (Client ID Metadata Documents, draft-ietf-oauth-client-id-metadata-document)
        // so ChatGPT can present a metadata-document URL as its client_id instead of registering first.
        // "automatic" means the AS will fetch the CIMD document on demand from the client_id URL.
        metadata.put("client_registration_types_supported", List.of("automatic"));

        return metadata;
    }

    // The DCR endpoint URL, derived from AuthorizationServerSettings (the path defaults to
    // "/connect/register") rather than hardcoded, so it tracks any issuer/path reconfiguration.
    private String registrationEndpoint(String issuer) {
        String path = authorizationServerSettings.getOidcClientRegistrationEndpoint();
        if (path.isBlank()) {
            path = "/connect/register";
        }
        return issuer + path;
    }
}
