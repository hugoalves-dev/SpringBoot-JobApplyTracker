package com.jobtracker.unit;

import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.service.semantic.EmbeddingProviderException;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient.EmbeddingBatch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouterEmbeddingClientTest {
    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MockRestServiceServer server;
    private OpenRouterEmbeddingClient client;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://openrouter.test/api/v1/");
        properties.setDimensions(2);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenRouterEmbeddingClient(properties, builder.build(), meters);
    }

    @Test
    void sendsBatchRequestAndReturnsNormalisedVectorsInInputOrder() {
        server.expect(requestTo("https://openrouter.test/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen/qwen3-embedding-8b"))
                .andExpect(jsonPath("$.input[1]").value("second"))
                .andExpect(jsonPath("$.dimensions").value(2))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0,2]},{"index":0,"embedding":[3,4]}],
                         "usage":{"prompt_tokens":7,"total_tokens":7,"cost":0.00002}}
                        """, MediaType.APPLICATION_JSON));

        EmbeddingBatch batch = client.embed(List.of("first", "second"), "document");

        assertThat(batch.vectors().get(0)[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(batch.vectors().get(0)[1]).isCloseTo(0.8f, within(1e-6f));
        assertThat(batch.vectors().get(1)).containsExactly(0f, 1f);
        assertThat(batch.promptTokens()).isEqualTo(7);
        assertThat(meters.counter("semantic.embeddings.tokens", "purpose", "document").count()).isEqualTo(7);
        assertThat(meters.counter("semantic.embeddings.cost.usd", "purpose", "document").count()).isEqualTo(0.00002);
        server.verify();
    }

    @Test
    void truncatesMatryoshkaVectorsWhenProviderIgnoresDimensions() {
        server.expect(requestTo("https://openrouter.test/api/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[3,4,99,99]}]}", MediaType.APPLICATION_JSON));

        float[] vector = client.embed(List.of("q"), "query").vectors().getFirst();

        assertThat(vector).hasSize(2);
        assertThat(vector[0]).isCloseTo(0.6f, within(1e-6f));
    }

    @Test
    void rejectsDimensionMismatchWhenTruncationIsDisabled() {
        properties.setTruncateToDimensions(false);
        server.expect(requestTo("https://openrouter.test/api/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[3,4,5]}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("q"), "query"))
                .isInstanceOf(EmbeddingProviderException.class).hasMessageContaining("dimension mismatch");
    }

    @Test
    void mapsProviderErrorsAndCountMismatches() {
        server.expect(requestTo("https://openrouter.test/api/v1/embeddings")).andRespond(withServerError());
        assertThatThrownBy(() -> client.embed(List.of("q"), "query")).isInstanceOf(EmbeddingProviderException.class);

        server.reset();
        server.expect(requestTo("https://openrouter.test/api/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embed(List.of("q"), "query"))
                .isInstanceOf(EmbeddingProviderException.class).hasMessageContaining("0 vectors for 1 inputs");
        assertThat(meters.timer("semantic.embeddings.latency", "purpose", "query",
                "model", "qwen/qwen3-embedding-8b", "outcome", "failure").count()).isEqualTo(2);
    }

    @Test
    void refusesToCallWhenNotConfigured() {
        properties.setApiKey("");
        assertThatThrownBy(() -> client.embed(List.of("q"), "query")).isInstanceOf(EmbeddingProviderException.class);
        server.verify();
    }
}
