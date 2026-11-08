package com.jobtracker.config;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * RFC 7591 registration policy and RFC 8252 loopback-port exception for DCR public clients.
 * All other authorization requests retain Spring Authorization Server's default URI validation.
 */
final class DcrRedirectUriPolicy {

    private DcrRedirectUriPolicy() {
    }

    static boolean isValidForRegistration(String value) {
        if (value == null || value.length() > 500) {
            return false;
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() > 65535) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme()) || isHttpLoopback(uri);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    static void validateAuthorizationRedirectUri(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        RegisteredClient client = context.getRegisteredClient();
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        String requestedRedirectUri = request.getRedirectUri();

        // DCR registers the full callback URI; RFC 8252 §7.3 permits changing ONLY its port.
        // Do not relax validation for static clients, CIMD clients, HTTPS callbacks or non-PKCE clients.
        if (requestedRedirectUri != null
                && client.getClientId().startsWith("dcr-")
                && client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                && client.getClientSettings().isRequireProofKey()
                && client.getRedirectUris().stream().anyMatch(
                        registered -> matchesHttpLoopbackIgnoringPort(registered, requestedRedirectUri))) {
            return;
        }

        OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR.accept(context);
    }

    private static boolean matchesHttpLoopbackIgnoringPort(String registered, String requested) {
        if (!isValidForRegistration(registered) || !isValidForRegistration(requested)) {
            return false;
        }

        URI registeredUri = URI.create(registered);
        URI requestedUri = URI.create(requested);
        return isHttpLoopback(registeredUri)
                && isHttpLoopback(requestedUri)
                && registeredUri.getHost().equalsIgnoreCase(requestedUri.getHost())
                && Objects.equals(registeredUri.getRawPath(), requestedUri.getRawPath())
                && Objects.equals(registeredUri.getRawQuery(), requestedUri.getRawQuery());
    }

    private static boolean isHttpLoopback(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equalsIgnoreCase(host);
    }
}
