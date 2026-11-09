package com.jobtracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the {@code app.mcp-oauth.dcr-enabled=false} switch.
 *
 * Disabling DCR must be consistent: the endpoint is denied AND the
 * registration_endpoint disappears from every discovery document. Advertising an
 * endpoint that answers 403 makes MCP clients (ChatGPT in particular) fail with a
 * generic connection error — which is worse than not advertising DCR at all.
 *
 * Note: with DCR disabled, ChatGPT's MCP connector cannot connect (it has no way to
 * supply a pre-registered client_id), so this switch should stay on while the
 * ChatGPT connector is in use.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobtracker_dcr_disabled_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.show-sql=false",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "app.mcp-oauth.dcr-enabled=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DcrDisabledIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerEndpoint_isDenied() throws Exception {
        mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "ChatGPT",
                                  "redirect_uris": ["https://chatgpt.com/connector/oauth/EGbtXUg8cJcN"],
                                  "grant_types": ["authorization_code"],
                                  "response_types": ["code"],
                                  "token_endpoint_auth_method": "none"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedResourceMetadata_omitsRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist());
    }

    @Test
    void authorizationServerMetadata_omitsRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist())
                .andExpect(jsonPath("$.token_endpoint").exists());
    }

    @Test
    void rootAuthorizationServerMetadata_omitsRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist());
    }

    @Test
    void oidcDiscovery_omitsRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist());
    }
}
