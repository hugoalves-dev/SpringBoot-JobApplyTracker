package com.jobtracker.unit;

import com.jobtracker.config.AuthorizationServerConfig;
import com.jobtracker.config.McpOAuthProperties;
import com.jobtracker.config.OAuthProtectedResourceMetadataController;
import com.jobtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuthQodanaRegressionTest {
    @Test
    void protectedMetadata_rejectsMissingIssuerWithConfigurationError() {
        var controller = new OAuthProtectedResourceMetadataController(
                AuthorizationServerSettings.builder().build(), new McpOAuthProperties());
        assertThatThrownBy(() -> controller.protectedResourceMetadata(new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("issuer");
    }

    @Test
    void authorizationMetadata_rejectsMissingIssuerWithConfigurationError() {
        var controller = new com.jobtracker.config.OAuthAuthorizationServerMetadataController(
                AuthorizationServerSettings.builder().build(), new McpOAuthProperties());
        assertThatThrownBy(() -> controller.authorizationServerMetadata(new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("issuer");
    }

    @Test
    void tokenCustomizer_ignoresAuthoritiesWithoutStringRepresentation() {
        var users = mock(UserRepository.class);
        var context = mock(JwtEncodingContext.class);
        var principal = UsernamePasswordAuthenticationToken.authenticated("user@example.com", "unused",
                List.<org.springframework.security.core.GrantedAuthority>of(() -> null, new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER")));
        var claims = JwtClaimsSet.builder();
        when(context.getTokenType()).thenReturn(OAuth2TokenType.ACCESS_TOKEN);
        doReturn(principal).when(context).getPrincipal();
        when(context.getClaims()).thenReturn(claims);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.empty());

        new AuthorizationServerConfig().jwtTokenCustomizer(users).customize(context);

        assertThat(claims.build().getClaimAsStringList("roles"))
                .containsExactly("ROLE_GPT_CLIENT", "ROLE_ADMIN");
    }

    @Test
    void tokenCustomizer_rejectsMissingPrincipal() {
        var context = mock(JwtEncodingContext.class);
        when(context.getTokenType()).thenReturn(OAuth2TokenType.ACCESS_TOKEN);
        assertThatThrownBy(() -> new AuthorizationServerConfig().jwtTokenCustomizer(mock(UserRepository.class)).customize(context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("principal");
    }
}
