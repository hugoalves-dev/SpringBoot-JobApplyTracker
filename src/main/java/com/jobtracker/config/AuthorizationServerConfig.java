package com.jobtracker.config;

import com.jobtracker.repository.UserRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.client.RestClient;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.function.Function;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties({GptOAuthProperties.class, McpOAuthProperties.class})
public class AuthorizationServerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain protectedResourceMetadataSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(request -> request.getRequestURI().startsWith("/.well-known/oauth-protected-resource"))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            AuthorizationServerSettings authorizationServerSettings,
            UserRepository userRepository,
            McpOAuthProperties mcpOAuthProperties,
            FormLoginRateLimitFilter formLoginRateLimitFilter,
            RegisteredClientRepository registeredClientRepository) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();
        RequestMatcher authServerMatcher = new OrRequestMatcher(
                endpointsMatcher,
                request -> "/login".equals(request.getServletPath()),
                // Static assets for the generated login page (served by DefaultResourcesFilter
                // within this chain); otherwise they fall through to the main chain and 403.
                request -> "/default-ui.css".equals(request.getServletPath()));

        String issuer = authorizationServerSettings.getIssuer();

        // Userinfo mapper: loads email and name from the user repository so that
        // /userinfo returns sub, email, and name for openid/profile/email scopes.
        Function<OidcUserInfoAuthenticationContext, OidcUserInfo> userInfoMapper = context -> {
            String principalName = context.getAuthorization().getPrincipalName();
            OidcUserInfo.Builder builder = OidcUserInfo.builder().subject(principalName);
            userRepository.findByEmail(principalName).ifPresent(user ->
                    builder.email(user.getEmail()).name(user.getName()));
            return builder.build();
        };

        http
                .securityMatcher(authServerMatcher)
                // Without CORS on this chain, browser-based public clients (ChatGPT does
                // the PKCE token exchange from chatgpt.com) have their preflight to
                // /oauth2/token rejected with 403 and can never redeem the code.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/default-ui.css").permitAll()
                        .anyRequest().authenticated())
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        // Public clients (Claude/ChatGPT MCP connectors) must be able to
                        // refresh tokens with just their client_id: SAS's built-in public
                        // client support only covers the PKCE authorization_code request.
                        .clientAuthentication(clientAuth -> clientAuth
                                .authenticationConverters(converters ->
                                        converters.add(new PublicClientRefreshTokenAuthenticationConverter()))
                                .authenticationProviders(providers ->
                                        providers.add(0, new PublicClientRefreshTokenAuthenticationProvider(
                                                registeredClientRepository))))
                        .oidc(oidc -> oidc
                                // Advertise DCR endpoint so ChatGPT (and other clients) can
                                // discover it from /.well-known/openid-configuration.
                                // Also ensure "none" appears in token_endpoint_auth_methods_supported
                                // so public-client flows are not rejected before they start.
                                .providerConfigurationEndpoint(endpoint -> endpoint
                                        .providerConfigurationCustomizer(metadata -> {
                                            if (mcpOAuthProperties.isDcrEnabled()) {
                                                metadata.claim("registration_endpoint",
                                                        issuer + "/connect/register");
                                            }
                                            metadata.tokenEndpointAuthenticationMethod("none");
                                        }))
                                .userInfoEndpoint(userInfo -> userInfo
                                        .userInfoMapper(userInfoMapper)))
                        .authorizationServerMetadataEndpoint(metadata ->
                                metadata.authorizationServerMetadataCustomizer(builder ->
                                        builder.tokenEndpointAuthenticationMethod(
                                                ClientAuthenticationMethod.NONE.getValue()))))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        request -> "/oauth2/token".equals(request.getServletPath()),
                        request -> "/oauth2/revoke".equals(request.getServletPath()),
                        request -> "/oauth2/introspect".equals(request.getServletPath())))
                .formLogin(Customizer.withDefaults())
                // Before CsrfFilter so every POST /login consumes a permit — otherwise
                // CSRF-rejected probes would not count against the brute-force budget.
                .addFilterBefore(formLoginRateLimitFilter, CsrfFilter.class);

        return http.build();
    }

    @Bean(name = "jdbcRegisteredClientRepository")
    public RegisteredClientRepository jdbcRegisteredClientRepository(JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    /**
     * Primary {@link RegisteredClientRepository}: adds CIMD (Client ID Metadata Document) support
     * on top of the persistent JDBC repository. URL client IDs are resolved as ephemeral CIMD
     * clients; all other lookups (bootstrap clients, DCR-registered clients) delegate to JDBC.
     */
    @Bean
    @Primary
    public RegisteredClientRepository registeredClientRepository(
            @Qualifier("jdbcRegisteredClientRepository") RegisteredClientRepository jdbcRegisteredClientRepository,
            McpOAuthProperties mcpOAuthProperties,
            @Qualifier("cimdRestClient") RestClient cimdRestClient) {
        return new CimdRegisteredClientRepository(jdbcRegisteredClientRepository, mcpOAuthProperties, cimdRestClient);
    }

    /**
     * Dedicated {@link RestClient} for fetching CIMD documents, with tight timeouts
     * (3s connect, 5s read) so a slow or hostile client_id URL cannot stall the authorization flow.
     */
    @Bean(name = "cimdRestClient")
    public RestClient cimdRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(GptOAuthProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.normalizedIssuer()).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(@Value("${jwt.secret}") String jwtSecret) {
        try {
            byte[] seed = MessageDigest.getInstance("SHA-256")
                    .digest(("authorization-server-rsa::" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
            secureRandom.setSeed(seed);

            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048, secureRandom);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("authorization-server-rsa")
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to initialize authorization server signing key", ex);
        }
    }

    @Bean
    public JwtDecoder authorizationServerJwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Replaces the default token generator so refresh tokens are also issued to public
     * clients (see {@link PublicClientRefreshTokenGenerator}). The JWT generator keeps
     * the existing {@link #jwtTokenCustomizer} so access tokens still carry
     * {@code user_id} and {@code roles}.
     */
    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(
            JWKSource<SecurityContext> jwkSource,
            OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(jwtTokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator(),
                new PublicClientRefreshTokenGenerator());
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(UserRepository userRepository) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }

            var principal = context.getPrincipal();
            if (principal == null) {
                throw new IllegalStateException("An access token requires an authenticated principal");
            }

            LinkedHashSet<String> roles = new LinkedHashSet<>();
            roles.add("ROLE_GPT_CLIENT");
            principal.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(Objects::nonNull)
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .filter(authority -> !"ROLE_USER".equals(authority))
                    .forEach(roles::add);

            context.getClaims().claim("roles", roles);
            userRepository.findByEmail(principal.getName())
                    .ifPresent(user -> context.getClaims().claim("user_id", user.getId().toString()));
        };
    }

    @Bean
    public ApplicationRunner registeredClientBootstrap(
            GptOAuthProperties properties,
            RegisteredClientRepository registeredClientRepository,
            JdbcOperations jdbcOperations,
            PasswordEncoder passwordEncoder) {
        return args -> {
            if (!properties.isConfigured()) {
                return;
            }

            RegisteredClient registeredClient = RegisteredClient.withId(stableId(properties.getClientId()))
                    .clientId(properties.getClientId())
                    .clientIdIssuedAt(Instant.now())
                    .clientSecret(passwordEncoder.encode(properties.getClientSecret()))
                    .clientName("OpenAI GPT Actions")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUris(uris -> uris.addAll(properties.getRedirectUris()))
                    .scopes(scopes -> scopes.addAll(properties.getScopes()))
                    .clientSettings(ClientSettings.builder()
                            .requireAuthorizationConsent(false)
                            .requireProofKey(false)
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .authorizationCodeTimeToLive(properties.getAuthorizationCodeTimeToLive())
                            .accessTokenTimeToLive(properties.getAccessTokenTimeToLive())
                            .refreshTokenTimeToLive(properties.getRefreshTokenTimeToLive())
                            .reuseRefreshTokens(false)
                            .build())
                    .build();

            RegisteredClient existing = registeredClientRepository.findByClientId(properties.getClientId());
            if (existing == null) {
                registeredClientRepository.save(registeredClient);
                return;
            }

            if (registeredClientRequiresRefresh(existing, registeredClient, passwordEncoder, properties)) {
                jdbcOperations.update("DELETE FROM oauth2_registered_client WHERE id = ?", existing.getId());
                registeredClientRepository.save(registeredClient);
            }
        };
    }

    @Bean
    public ApplicationRunner mcpRegisteredClientBootstrap(
            McpOAuthProperties properties,
            RegisteredClientRepository registeredClientRepository,
            JdbcOperations jdbcOperations,
            PasswordEncoder passwordEncoder) {
        return args -> {
            if (!properties.isConfigured()) {
                return;
            }

            boolean hasSecret = properties.getClientSecret() != null && !properties.getClientSecret().isBlank();

            RegisteredClient.Builder builder = RegisteredClient.withId(stableId(properties.getClientId()))
                    .clientId(properties.getClientId())
                    .clientIdIssuedAt(Instant.now())
                    .clientName("MCP Client")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUris(uris -> uris.addAll(properties.getRedirectUris()))
                    .scopes(scopes -> scopes.addAll(properties.getScopes()))
                    .clientSettings(ClientSettings.builder()
                            .requireAuthorizationConsent(false)
                            .requireProofKey(true)
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(properties.getAccessTokenTimeToLive())
                            .refreshTokenTimeToLive(properties.getRefreshTokenTimeToLive())
                            .reuseRefreshTokens(false)
                            .build());

            if (hasSecret) {
                builder
                        .clientSecret(passwordEncoder.encode(properties.getClientSecret()))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
            } else {
                builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
            }

            RegisteredClient registeredClient = builder.build();

            RegisteredClient existing = registeredClientRepository.findByClientId(properties.getClientId());
            if (existing == null) {
                registeredClientRepository.save(registeredClient);
                return;
            }

            boolean secretChanged = hasSecret && !passwordEncoder.matches(properties.getClientSecret(), existing.getClientSecret());
            boolean redirectsChanged = !new LinkedHashSet<>(existing.getRedirectUris()).equals(new LinkedHashSet<>(registeredClient.getRedirectUris()));
            boolean scopesChanged = !new LinkedHashSet<>(existing.getScopes()).equals(new LinkedHashSet<>(registeredClient.getScopes()));

            if (secretChanged || redirectsChanged || scopesChanged) {
                jdbcOperations.update("DELETE FROM oauth2_registered_client WHERE id = ?", existing.getId());
                registeredClientRepository.save(registeredClient);
            }
        };
    }

    private static String stableId(String clientId) {
        return UUID.nameUUIDFromBytes(clientId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean registeredClientRequiresRefresh(
            RegisteredClient existing,
            RegisteredClient desiredClient,
            PasswordEncoder passwordEncoder,
            GptOAuthProperties properties) {
        return !passwordEncoder.matches(properties.getClientSecret(), existing.getClientSecret())
                || !new LinkedHashSet<>(existing.getRedirectUris()).equals(new LinkedHashSet<>(desiredClient.getRedirectUris()))
                || !new LinkedHashSet<>(existing.getScopes()).equals(new LinkedHashSet<>(desiredClient.getScopes()))
                || !existing.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                || !existing.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                || !existing.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE)
                || !existing.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)
                || existing.getClientSettings().isRequireProofKey() != desiredClient.getClientSettings().isRequireProofKey()
                || existing.getClientSettings().isRequireAuthorizationConsent() != desiredClient.getClientSettings().isRequireAuthorizationConsent();
    }
}
