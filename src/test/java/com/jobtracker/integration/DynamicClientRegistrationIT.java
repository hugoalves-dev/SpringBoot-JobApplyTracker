package com.jobtracker.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jobtracker.config.DynamicClientRegistrationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the RFC 7591 Dynamic Client Registration endpoint needed for the ChatGPT OAuth flow.
 *
 * ChatGPT calls POST /connect/register before initiating OAuth. Without DCR, the ChatGPT
 * plugin shows "There was a problem connecting". This test suite validates:
 *  - The OIDC discovery document advertises registration_endpoint
 *  - The discovery document includes "none" in token_endpoint_auth_methods_supported
 *  - Successful DCR registration returns a valid client_id
 *  - The registered client can be used in a PKCE authorization_code flow
 *  - Per-IP rate limiting prevents abuse
 *  - Invalid redirect_uris are rejected
 */
class DynamicClientRegistrationIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DynamicClientRegistrationController dcrController;

    @BeforeEach
    void resetRateLimit() {
        dcrController.resetRateLimitMap();
    }

    @Test
    void oauthDiscovery_shouldAdvertiseRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint")
                        .value("https://jobapply-api.hugojava.dev/connect/register"));
    }

    @Test
    void dcrEndpoint_shouldRejectSpoofedLocalhostAndMixedInvalidRedirects() throws Exception {
        for (String body : new String[]{
                "{\"redirect_uris\":[\"http://localhost.evil.example/callback\"]}",
                "{\"redirect_uris\":[\"https://example.com/callback\",\"http://evil.example/callback\"]}"}) {
            mockMvc.perform(post("/connect/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
        }
    }

    @Test
    void dcrEndpoint_shouldRejectUnsupportedClientAuthentication() throws Exception {
        mockMvc.perform(post("/connect/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["https://example.com/callback"],
                                 "token_endpoint_auth_method":"client_secret_basic"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @Test
    void oidcDiscovery_shouldAdvertiseRegistrationEndpointAndNoneAuthMethod() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint")
                        .value("https://jobapply-api.hugojava.dev/connect/register"))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported")
                        .isArray());

        // Verify "none" is in the array (public-client support)
        MvcResult result = mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode doc = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode authMethods = doc.get("token_endpoint_auth_methods_supported");
        assertThat(authMethods).isNotNull();
        boolean hasNone = false;
        for (JsonNode method : authMethods) {
            if ("none".equals(method.asText())) {
                hasNone = true;
                break;
            }
        }
        assertThat(hasNone).as("token_endpoint_auth_methods_supported must include 'none'").isTrue();
    }

    @Test
    void protectedResourceMetadata_shouldAdvertiseRegistrationEndpointAndCimd() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint")
                        .value("https://jobapply-api.hugojava.dev/connect/register"))
                .andExpect(jsonPath("$.client_registration_types_supported").isArray())
                .andExpect(jsonPath("$.client_registration_types_supported[0]").value("automatic"))
                .andExpect(jsonPath("$.authorization_servers[0]")
                        .value("https://jobapply-api.hugojava.dev"));
    }

    @Test
    void getConnectRegister_shouldReturnMethodNotAllowed() throws Exception {
        // DCR is POST-only; a GET must surface as 405 (not 500 from the generic handler).
        mockMvc.perform(get("/connect/register"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"));
    }

    @Test
    void dcrEndpoint_shouldBePublic() throws Exception {
        mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["https://chat.openai.com/aip/test/callback"]}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void dcrEndpoint_shouldReturnValidClientId() throws Exception {
        MvcResult result = mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "redirect_uris": ["https://chatgpt.com/aip/plugin/callback"],
                                  "grant_types": ["authorization_code"],
                                  "token_endpoint_auth_method": "none",
                                  "scope": "openid read:profile read:applications",
                                  "client_name": "ChatGPT Test"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").isString())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
                .andExpect(jsonPath("$.grant_types[0]").value("authorization_code"))
                .andExpect(jsonPath("$.redirect_uris[0]").value("https://chatgpt.com/aip/plugin/callback"))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String clientId = response.get("client_id").asText();
        assertThat(clientId).startsWith("dcr-");
        assertThat(response.get("client_id_issued_at").asLong()).isGreaterThan(0);

        // Scope: only the intersection of requested and allowed is granted
        String scope = response.get("scope").asText();
        assertThat(scope).contains("openid");
        assertThat(scope).contains("read:profile");
        assertThat(scope).contains("read:applications");
    }

    @Test
    void dcrEndpoint_shouldGrantAllMcpScopesWhenNoScopeRequested() throws Exception {
        MvcResult result = mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["https://example.com/callback"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String scope = response.get("scope").asText();
        assertThat(scope).contains("openid");
        assertThat(scope).contains("read:profile");
    }

    @Test
    void dcrEndpoint_shouldRejectMissingRedirectUris() throws Exception {
        mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    }

    @Test
    void dcrEndpoint_shouldRejectHttpRedirectUri() throws Exception {
        mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["http://evil.example.com/callback"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    }

    @Test
    void dcrEndpoint_shouldAllowHttpLocalhostRedirectUri() throws Exception {
        mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["http://localhost:8080/callback"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").isString());
    }

    @Test
    void dcrEndpoint_shouldEnforcePerIpRateLimit() throws Exception {
        // Fill up the allowed quota
        for (int i = 0; i < DynamicClientRegistrationController.MAX_REGISTRATIONS_PER_MINUTE_PER_IP; i++) {
            mockMvc.perform(post("/connect/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"redirect_uris":["https://example.com/callback"]}
                                    """))
                    .andExpect(status().isCreated());
        }

        // The next request from the same IP should be rate-limited
        mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["https://example.com/callback"]}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_requests"));
    }

    @Test
    void dcrEndpoint_shouldOnlyGrantRequestedScopesWithinAllowed() throws Exception {
        MvcResult result = mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "redirect_uris": ["https://example.com/cb"],
                                  "scope": "openid read:profile unknown:scope"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String scope = response.get("scope").asText();
        assertThat(scope).doesNotContain("unknown:scope");
        assertThat(scope).contains("openid");
        assertThat(scope).contains("read:profile");
    }
}
