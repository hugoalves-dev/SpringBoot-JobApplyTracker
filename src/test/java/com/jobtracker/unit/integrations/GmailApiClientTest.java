package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.service.integrations.gmail.DraftOutcomeUnknownException;
import com.jobtracker.service.integrations.gmail.GmailApiClient;
import com.jobtracker.service.integrations.gmail.GmailAuthenticationException;
import com.jobtracker.service.integrations.gmail.GmailReauthorizationRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GmailApiClientTest {

    private final IntegrationProperties properties = new IntegrationProperties();
    private MockRestServiceServer server;
    private GmailApiClient client;

    @BeforeEach
    void setUp() {
        IntegrationProperties.Gmail gmail = properties.getGmail();
        gmail.setClientId("client-id");
        gmail.setClientSecret("client-secret");
        gmail.setRedirectUri("https://api.test/api/v1/integrations/gmail/oauth/callback");
        gmail.setOauthCompleteUrl("https://app.test/account");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GmailApiClient(properties, builder.build());
    }

    @Test
    void authorizationUrlRequestsOnlyComposeScopeWithOfflineAccessAndPkce() {
        String url = client.buildAuthorizationUrl("state-1", "challenge-1");
        Map<String, String> params = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();

        assertThat(java.net.URLDecoder.decode(params.get("scope"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("https://www.googleapis.com/auth/gmail.compose");
        assertThat(params).containsEntry("access_type", "offline")
                .containsEntry("code_challenge", "challenge-1")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("state", "state-1");
        assertThat(url).doesNotContain("gmail.send").doesNotContain("mail.google.com");
    }

    @Test
    void exchangesCodeWithVerifier() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("code", "abc", "code_verifier", "verifier", "grant_type", "authorization_code")))
                .andRespond(withSuccess("""
                        {"access_token":"at","refresh_token":"rt","expires_in":3599,"scope":"https://www.googleapis.com/auth/gmail.compose"}
                        """, MediaType.APPLICATION_JSON));

        GmailApiClient.OAuthTokens tokens = client.exchangeCode("abc", "verifier");

        assertThat(tokens.accessToken()).isEqualTo("at");
        assertThat(tokens.refreshToken()).isEqualTo("rt");
    }

    @Test
    void invalidGrantOnRefreshRequiresReauthorization() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(GmailReauthorizationRequiredException.class);
    }

    @Test
    void createsDraftWithRawMessage() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/drafts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer at"))
                .andExpect(jsonPath("$.message.raw").value("cmF3"))
                .andRespond(withSuccess("{\"id\":\"r-1\",\"message\":{\"id\":\"m-1\",\"threadId\":\"t-1\"}}",
                        MediaType.APPLICATION_JSON));

        GmailApiClient.CreatedDraft draft = client.createDraft("at", "cmF3");

        assertThat(draft.draftId()).isEqualTo("r-1");
        assertThat(draft.messageId()).isEqualTo("m-1");
    }

    @Test
    void serverErrorsAndTimeoutsOnDraftCreationAreAmbiguous() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/drafts"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.createDraft("at", "cmF3")).isInstanceOf(DraftOutcomeUnknownException.class);

        server.reset();
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/drafts"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> client.createDraft("at", "cmF3")).isInstanceOf(DraftOutcomeUnknownException.class);
    }

    @Test
    void unauthorizedAndForbiddenAreDefinitive() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/drafts"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.createDraft("at", "cmF3")).isInstanceOf(GmailAuthenticationException.class);

        server.reset();
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/drafts"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.createDraft("at", "cmF3")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void readsProfileEmail() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andRespond(withSuccess("{\"emailAddress\":\"dev@gmail.com\"}", MediaType.APPLICATION_JSON));

        assertThat(client.getProfileEmail("at")).isEqualTo("dev@gmail.com");
    }

    @Test
    void revokeIsBestEffort() throws IOException {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andRespond(withException(new IOException("down")));

        assertThat(client.revoke("rt")).isFalse();
    }
}
