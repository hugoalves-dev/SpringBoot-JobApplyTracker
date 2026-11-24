package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchClient;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TinyFetchClientTest {

    private static final String ENDPOINT = "https://api.fetch.tinyfish.test";

    private final IntegrationProperties properties = new IntegrationProperties();
    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private TinyFetchClient client;

    @BeforeEach
    void setUp() {
        properties.getTinyfetch().setBaseUrl(ENDPOINT);
        properties.getTinyfetch().setMaxAttempts(3);
        properties.getTinyfetch().setRetryBaseDelay(Duration.ofMillis(100));
        properties.getTinyfetch().setMaxRetryDelay(Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TinyFetchClient(properties, builder.build(), sleeps::add);
    }

    @Test
    void sendsKeyAndUrlAndParsesResult() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "tf-key"))
                .andExpect(jsonPath("$.urls[0]").value("https://jobs.example.com/1"))
                .andExpect(jsonPath("$.format").value("markdown"))
                .andRespond(withSuccess("""
                        {"results":[{"url":"https://jobs.example.com/1","final_url":"https://jobs.example.com/1?x",
                          "title":"Java Developer","text":"# Java Developer\\nRemote","language":"en","nested":{"a":1}}]}
                        """, MediaType.APPLICATION_JSON));

        TinyFetchClient.FetchOutcome outcome = client.fetch("tf-key", "https://jobs.example.com/1");

        assertThat(outcome.title()).isEqualTo("Java Developer");
        assertThat(outcome.finalUrl()).isEqualTo("https://jobs.example.com/1?x");
        assertThat(outcome.content()).startsWith("# Java Developer");
        assertThat(outcome.metadata()).containsEntry("language", "en").doesNotContainKey("nested");
        assertThat(outcome.errorCode()).isNull();
        server.verify();
    }

    @Test
    void reportsPerUrlErrors() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"results\":[],\"errors\":[{\"url\":\"https://x\",\"error\":\"bot_blocked\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.fetch("k", "https://x").errorCode()).isEqualTo("bot_blocked");
    }

    @Test
    void retriesRateLimitWithBackoffThenSucceeds() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .headers(retryAfter("2")));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"results\":[{\"text\":\"ok\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.fetch("k", "https://x").content()).isEqualTo("ok");
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    @Test
    void doesNotWaitWhenRetryAfterExceedsCap() {
        server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter("120")));

        assertThatThrownBy(() -> client.fetch("k", "https://x"))
                .isInstanceOfSatisfying(TinyFetchException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(TinyFetchException.Kind.RATE_LIMITED);
                    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(120));
                });
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void givesUpAfterMaxAttemptsOnServerErrors() {
        server.expect(ExpectedCount.times(3), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.fetch("k", "https://x"))
                .isInstanceOfSatisfying(TinyFetchException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(TinyFetchException.Kind.UPSTREAM));
        assertThat(sleeps).hasSize(2);
        server.verify();
    }

    @Test
    void doesNotRetryInvalidKey() {
        server.expect(ExpectedCount.once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetch("bad", "https://x"))
                .isInstanceOfSatisfying(TinyFetchException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(TinyFetchException.Kind.UNAUTHORIZED));
        assertThat(sleeps).isEmpty();
    }

    private static HttpHeaders retryAfter(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, value);
        return headers;
    }
}
