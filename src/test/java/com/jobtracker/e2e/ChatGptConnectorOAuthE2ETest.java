package com.jobtracker.e2e;

import io.restassured.RestAssured;
import io.restassured.filter.cookie.CookieFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.jobtracker.repository.UserRepository;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates, end to end against a real servlet container, exactly what the ChatGPT MCP
 * connector does when a user connects it:
 *
 * <ol>
 *   <li>Probe {@code POST /mcp} without a token — the MCP spec (and ChatGPT, strictly)
 *       requires {@code 401 Unauthorized} with a {@code WWW-Authenticate} header pointing
 *       at the RFC 9728 protected-resource metadata. Claude tolerates other 4xx statuses;
 *       ChatGPT aborts with a generic connection error if this is not a 401.</li>
 *   <li>Fetch the protected-resource metadata and the RFC 8414 path-aware authorization
 *       server metadata.</li>
 *   <li>Register a client via RFC 7591 Dynamic Client Registration (ChatGPT-shaped payload,
 *       including {@code refresh_token} in grant_types).</li>
 *   <li>Run the authorization-code flow with PKCE and the RFC 8707 {@code resource}
 *       parameter, logging in through the real form-login page (CSRF + session cookies),
 *       exactly like the browser window ChatGPT opens.</li>
 *   <li>Exchange the code at the token endpoint with client auth {@code none}
 *       (public client + PKCE) and the {@code resource} parameter.</li>
 *   <li>Call {@code POST /mcp} with the issued Bearer token.</li>
 * </ol>
 *
 * These run against a real Tomcat ({@code RANDOM_PORT}) on purpose: the historical
 * 401→403 regression was caused by the container's ERROR dispatch being re-processed by
 * the security filter chain, which MockMvc never exercises.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Mirror production: a configured MCP OAuth client so scopes_supported is populated.
                "app.mcp-oauth.client-id=jobapplytracker",
                "app.mcp-oauth.redirect-uris=https://chatgpt.com/connector/oauth/EGbtXUg8cJcN,https://claude.ai/api/mcp/auth_callback",
                "app.mcp-oauth.scopes=openid,read:profile,read:applications,write:applications,read:resume,read:metrics"
        })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatGptConnectorOAuthE2ETest {

    private static final String CHATGPT_REDIRECT_URI = "https://chatgpt.com/connector/oauth/EGbtXUg8cJcN";
    private static final String USER_EMAIL = "chatgpt-connector@example.com";
    private static final String USER_PASSWORD = "pass1234";

    private static final String MCP_INITIALIZE_BODY = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": { "name": "openai-mcp", "version": "1.0.0" }
              }
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        if (userRepository.findByEmail(USER_EMAIL).isEmpty()) {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "name": "ChatGPT Connector User",
                              "email": "%s",
                              "password": "%s",
                              "confirmPassword": "%s",
                              "acceptedPrivacyPolicy": true
                            }
                            """.formatted(USER_EMAIL, USER_PASSWORD, USER_PASSWORD))
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);
        }
    }

    /**
     * Step 0 — the unauthenticated probe. This is the assertion that reproduces the
     * production failure: through a real container, sendError(401) triggered an ERROR
     * dispatch to /error which the security chain rejected, downgrading the response
     * to 403 (with the WWW-Authenticate header surviving). ChatGPT requires the 401.
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    void unauthenticatedMcpProbe_mustReturn401WithResourceMetadataChallenge() {
        Response response = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body(MCP_INITIALIZE_BODY)
                .post("/mcp");

        assertThat(response.statusCode())
                .as("MCP spec: unauthenticated requests MUST get 401 (ChatGPT aborts on anything else)")
                .isEqualTo(401);
        assertThat(response.header("WWW-Authenticate"))
                .as("RFC 9728 §5.1 challenge with resource_metadata")
                .contains("Bearer")
                .contains("resource_metadata=")
                .contains("/.well-known/oauth-protected-resource/mcp");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void discoveryDocuments_advertiseEverythingChatGptNeeds() {
        // RFC 9728 protected resource metadata (path-aware)
        Response prm = given().accept(ContentType.JSON).get("/.well-known/oauth-protected-resource/mcp");
        assertThat(prm.statusCode()).isEqualTo(200);
        assertThat(prm.jsonPath().getString("resource")).endsWith("/mcp");
        assertThat(prm.jsonPath().getList("authorization_servers", String.class)).isNotEmpty();
        assertThat(prm.jsonPath().getString("registration_endpoint"))
                .as("ChatGPT only enables DCR when the protected-resource metadata advertises it")
                .endsWith("/connect/register");

        // RFC 8414 path-aware AS metadata (ChatGPT appends the resource path suffix)
        Response asm = given().accept(ContentType.JSON).get("/.well-known/oauth-authorization-server/mcp");
        assertThat(asm.statusCode()).isEqualTo(200);
        assertThat(asm.jsonPath().getString("authorization_endpoint")).endsWith("/oauth2/authorize");
        assertThat(asm.jsonPath().getString("token_endpoint")).endsWith("/oauth2/token");
        assertThat(asm.jsonPath().getString("registration_endpoint")).endsWith("/connect/register");
        assertThat(asm.jsonPath().getList("token_endpoint_auth_methods_supported", String.class))
                .as("public PKCE clients are rejected by ChatGPT unless 'none' is advertised")
                .contains("none");
        assertThat(asm.jsonPath().getList("code_challenge_methods_supported", String.class)).contains("S256");
    }

    /**
     * Steps 1-5 — the full connector handshake: DCR → authorize (PKCE + resource) →
     * form login → code → token (client auth none) → authenticated MCP call.
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    void fullChatGptConnectorFlow_dcrToAuthenticatedMcpCall() throws Exception {
        // --- Step 1: Dynamic Client Registration (ChatGPT-shaped payload) ---
        Response dcr = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "client_name": "ChatGPT",
                          "redirect_uris": ["%s"],
                          "grant_types": ["authorization_code", "refresh_token"],
                          "response_types": ["code"],
                          "token_endpoint_auth_method": "none"
                        }
                        """.formatted(CHATGPT_REDIRECT_URI))
                .post("/connect/register");

        assertThat(dcr.statusCode())
                .as("ChatGPT requires RFC 7591 registration to succeed")
                .isEqualTo(201);
        String clientId = dcr.jsonPath().getString("client_id");
        assertThat(clientId).isNotBlank();
        assertThat(dcr.jsonPath().getString("token_endpoint_auth_method")).isEqualTo("none");
        assertThat(dcr.jsonPath().getList("grant_types", String.class))
                .as("the granted grant_types should honour the requested refresh_token grant")
                .contains("authorization_code", "refresh_token");
        String scope = dcr.jsonPath().getString("scope");
        assertThat(scope).isNotBlank();

        // --- Step 2: authorization request with PKCE + RFC 8707 resource parameter ---
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = sha256UrlSafe(codeVerifier);
        String resource = "https://jobapply-api.hugojava.dev/mcp";
        String state = "chatgpt-state-" + System.nanoTime();

        CookieFilter browserCookies = new CookieFilter();

        Response authorize = given()
                .filter(browserCookies)
                .redirects().follow(false)
                .accept("text/html,application/xhtml+xml")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", CHATGPT_REDIRECT_URI)
                .queryParam("scope", scope)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("resource", resource)
                .get("/oauth2/authorize");

        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(authorize.header("Location"))
                .as("anonymous authorize request must bounce to the login form, not error out")
                .contains("/login");

        // --- Step 3: form login like the browser window ChatGPT opens ---
        Response loginPage = given()
                .filter(browserCookies)
                .accept("text/html")
                .get("/login");
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String csrf = extractCsrfToken(loginPage.asString());

        Response login = given()
                .filter(browserCookies)
                .redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("username", USER_EMAIL)
                .formParam("password", USER_PASSWORD)
                .formParam("_csrf", csrf)
                .post("/login");

        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.header("Location"))
                .as("successful login must resume the saved authorize request")
                .contains("/oauth2/authorize");

        // --- Step 4: resumed authorize request issues the code (consent is off) ---
        Response resumed = given()
                .filter(browserCookies)
                .redirects().follow(false)
                // the Location header is already URL-encoded; don't re-encode it
                .urlEncodingEnabled(false)
                .accept("text/html")
                .get(login.header("Location"));

        assertThat(resumed.statusCode()).isEqualTo(302);
        String callback = resumed.header("Location");
        assertThat(callback)
                .as("authorize must redirect back to ChatGPT's callback with a code")
                .startsWith(CHATGPT_REDIRECT_URI);
        Map<String, String> callbackParams = queryParams(callback);
        assertThat(callbackParams.get("state")).isEqualTo(state);
        String code = callbackParams.get("code");
        assertThat(code).isNotBlank();

        // --- Step 5: token exchange, public client (auth method none) + PKCE ---
        // ChatGPT runs this exchange from the user's browser (origin chatgpt.com), so
        // the browser first sends a CORS preflight. If it is rejected, the POST never
        // happens, the code is never redeemed, and the connector fails generically.
        Response preflight = given()
                .header("Origin", "https://chatgpt.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .options("/oauth2/token");
        assertThat(preflight.statusCode())
                .as("CORS preflight from chatgpt.com must be accepted on the token endpoint")
                .isEqualTo(200);
        assertThat(preflight.header("Access-Control-Allow-Origin")).isNotNull();

        Response token = given()
                .header("Origin", "https://chatgpt.com")
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", CHATGPT_REDIRECT_URI)
                .formParam("client_id", clientId)
                .formParam("code_verifier", codeVerifier)
                .formParam("resource", resource)
                .post("/oauth2/token");

        assertThat(token.statusCode())
                .as("token exchange for the DCR public client must succeed: %s", token.asString())
                .isEqualTo(200);
        assertThat(token.header("Access-Control-Allow-Origin"))
                .as("the browser also requires CORS headers on the actual token response")
                .isNotNull();
        assertThat(token.jsonPath().getString("token_type")).isEqualToIgnoringCase("Bearer");
        String accessToken = token.jsonPath().getString("access_token");
        assertThat(accessToken).isNotBlank();
        assertThat(token.jsonPath().getString("refresh_token"))
                .as("public clients with the refresh_token grant must receive a refresh token — "
                        + "without it the connector dies when the short-lived access token expires")
                .isNotBlank();

        // --- Step 5b: ChatGPT has "OIDC enabled" and calls /userinfo with the access
        // token right after the exchange (authorization domain claiming). Spring AS
        // requires oauth2ResourceServer on the AS chain for this endpoint to accept
        // Bearer tokens — without it ChatGPT fails here even though tokens were issued.
        Response userinfo = given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + accessToken)
                .get("/userinfo");
        assertThat(userinfo.statusCode())
                .as("OIDC userinfo must accept the issued Bearer token: %s", userinfo.asString())
                .isEqualTo(200);
        assertThat(userinfo.jsonPath().getString("sub")).isEqualTo(USER_EMAIL);
        assertThat(userinfo.jsonPath().getString("email")).isEqualTo(USER_EMAIL);

        // --- Step 6: the authenticated MCP call ChatGPT makes right after connecting ---
        Response mcp = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + accessToken)
                .body(MCP_INITIALIZE_BODY)
                .post("/mcp");

        assertThat(mcp.statusCode())
                .as("initialize with the issued token must not be rejected: %s", mcp.asString())
                .isEqualTo(200);

        String sessionId = mcp.header("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        Response tools = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + accessToken)
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """)
                .post("/mcp");
        assertThat(tools.statusCode()).isEqualTo(200);
        String toolsJson = tools.asString().lines().filter(line -> line.startsWith("data:"))
                .findFirst().map(line -> line.substring(5).trim()).orElse(tools.asString());
        assertThat(new io.restassured.path.json.JsonPath(toolsJson)
                .getList("result.tools.name", String.class)).contains("List-Applications");

        // --- Step 7: refresh exchange, exactly how a public MCP client renews after the
        // 15-minute access token expires: client_id only, no secret, no PKCE params.
        String refreshToken = token.jsonPath().getString("refresh_token");
        Response refreshed = given()
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "refresh_token")
                .formParam("refresh_token", refreshToken)
                .formParam("client_id", clientId)
                .post("/oauth2/token");

        assertThat(refreshed.statusCode())
                .as("public-client refresh must succeed: %s", refreshed.asString())
                .isEqualTo(200);
        String refreshedAccessToken = refreshed.jsonPath().getString("access_token");
        assertThat(refreshedAccessToken).isNotBlank();
        assertThat(refreshed.jsonPath().getString("refresh_token"))
                .as("refresh tokens must rotate for public clients (OAuth 2.1)")
                .isNotBlank()
                .isNotEqualTo(refreshToken);

        // the refreshed access token must keep the identity claims the MCP tools rely on
        Map<String, Object> refreshedClaims = decodeJwtPayload(refreshedAccessToken);
        assertThat(refreshedClaims.get("sub")).isEqualTo(USER_EMAIL);
        assertThat(refreshedClaims.get("user_id"))
                .as("user_id claim must survive the refresh grant — SecurityUtils resolves the user from it")
                .isNotNull();

        Response mcpAfterRefresh = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + refreshedAccessToken)
                .body(MCP_INITIALIZE_BODY)
                .post("/mcp");
        assertThat(mcpAfterRefresh.statusCode())
                .as("the refreshed token must keep working on /mcp: %s", mcpAfterRefresh.asString())
                .isEqualTo(200);
    }

    private static Map<String, Object> decodeJwtPayload(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        byte[] json = Base64.getUrlDecoder().decode(payload);
        return new tools.jackson.databind.ObjectMapper()
                .readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /**
     * ChatGPT (unlike Claude) requests scopes taken from the discovery documents. If it
     * asks for a scope the DCR client was not granted, Spring AS redirects back to the
     * callback with error=invalid_scope — which surfaces as the generic connector error.
     * Guard that every advertised scope is grantable to a DCR client.
     */
    @Test
    @org.junit.jupiter.api.Order(4)
    void advertisedScopes_areAllGrantableToDcrClients() {
        Response prm = given().accept(ContentType.JSON).get("/.well-known/oauth-protected-resource/mcp");
        java.util.List<String> advertised = prm.jsonPath().getList("scopes_supported", String.class);
        assertThat(advertised).isNotEmpty();

        Response dcr = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "client_name": "ChatGPT scope probe",
                          "redirect_uris": ["%s"],
                          "grant_types": ["authorization_code"],
                          "response_types": ["code"],
                          "token_endpoint_auth_method": "none",
                          "scope": "%s"
                        }
                        """.formatted(CHATGPT_REDIRECT_URI, String.join(" ", advertised)))
                .post("/connect/register");

        assertThat(dcr.statusCode()).isEqualTo(201);
        assertThat(dcr.jsonPath().getString("scope").split(" "))
                .as("every scope advertised in scopes_supported must be granted on registration")
                .containsExactlyInAnyOrderElementsOf(advertised);
    }

    // --- helpers ---

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256UrlSafe(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String extractCsrfToken(String loginPageHtml) {
        Matcher matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPageHtml);
        assertThat(matcher.find())
                .as("login page must contain the hidden _csrf input")
                .isTrue();
        return matcher.group(1);
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
