package com.jobtracker.service.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jobtracker.config.SemanticSearchProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Thin client for OpenRouter's OpenAI-compatible {@code POST /embeddings}. */
@Component
public class OpenRouterEmbeddingClient {
    private final SemanticSearchProperties properties;
    private final RestClient restClient;
    private final MeterRegistry meters;

    @Autowired
    public OpenRouterEmbeddingClient(SemanticSearchProperties properties, RestClient.Builder builder,
                                     MeterRegistry meters) {
        this(properties, builder.requestFactory(requestFactory(properties)).build(), meters);
    }

    /** Explicit wiring for tests. */
    public OpenRouterEmbeddingClient(SemanticSearchProperties properties, RestClient restClient, MeterRegistry meters) {
        this.properties = properties;
        this.restClient = restClient;
        this.meters = meters;
    }

    /**
     * @param purpose metric tag: "document" for background indexing, "query" for interactive search.
     * @return one normalised vector per input, in input order.
     */
    public EmbeddingBatch embed(List<String> inputs, String purpose) {
        if (inputs.isEmpty()) return new EmbeddingBatch(List.of(), 0, null);
        if (!properties.isConfigured()) throw new EmbeddingProviderException("Semantic search is not configured");
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            EmbeddingResponse response = restClient.post()
                    .uri(trimTrailingSlash(properties.getBaseUrl()) + "/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbeddingRequest(properties.getModel(), inputs, properties.getDimensions(), "float"))
                    .retrieve()
                    .body(EmbeddingResponse.class);
            EmbeddingBatch batch = toBatch(response, inputs.size());
            meters.counter("semantic.embeddings.inputs", "purpose", purpose).increment(inputs.size());
            meters.counter("semantic.embeddings.tokens", "purpose", purpose).increment(batch.promptTokens());
            if (batch.costUsd() != null) {
                meters.counter("semantic.embeddings.cost.usd", "purpose", purpose).increment(batch.costUsd());
            }
            return batch;
        } catch (RestClientException ex) {
            outcome = "failure";
            throw new EmbeddingProviderException("Embedding provider request failed: " + ex.getMessage(), ex);
        } catch (EmbeddingProviderException ex) {
            outcome = "failure";
            throw ex;
        } finally {
            sample.stop(meters.timer("semantic.embeddings.latency", "purpose", purpose,
                    "model", properties.getModel(), "outcome", outcome));
        }
    }

    private EmbeddingBatch toBatch(EmbeddingResponse response, int expected) {
        if (response == null || response.data() == null || response.data().size() != expected) {
            throw new EmbeddingProviderException("Embedding provider returned " +
                    (response == null || response.data() == null ? 0 : response.data().size()) +
                    " vectors for " + expected + " inputs");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingData::index))
                .forEach(data -> vectors.add(fitDimensions(data.embedding())));
        long tokens = response.usage() == null || response.usage().promptTokens() == null
                ? 0 : response.usage().promptTokens();
        Double cost = response.usage() == null ? null : response.usage().cost();
        return new EmbeddingBatch(vectors, tokens, cost);
    }

    private float[] fitDimensions(List<Double> raw) {
        int target = properties.getDimensions();
        if (raw == null || raw.isEmpty()) throw new EmbeddingProviderException("Embedding provider returned an empty vector");
        if (raw.size() < target || (raw.size() > target && !properties.isTruncateToDimensions())) {
            throw new EmbeddingProviderException("Embedding dimension mismatch: expected " + target + ", got " + raw.size());
        }
        float[] vector = new float[target];
        double norm = 0;
        for (int i = 0; i < target; i++) {
            double value = raw.get(i);
            if (!Double.isFinite(value)) throw new EmbeddingProviderException("Embedding contains a non-finite value");
            vector[i] = (float) value;
            norm += value * value;
        }
        if (norm == 0) throw new EmbeddingProviderException("Embedding provider returned a zero vector");
        double scale = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < target; i++) vector[i] = (float) (vector[i] * scale);
        return vector;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static SimpleClientHttpRequestFactory requestFactory(SemanticSearchProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getRequestTimeout());
        factory.setReadTimeout(properties.getRequestTimeout());
        return factory;
    }

    public record EmbeddingBatch(List<float[]> vectors, long promptTokens, Double costUsd) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EmbeddingRequest(String model, List<String> input, Integer dimensions,
                            @JsonProperty("encoding_format") String encodingFormat) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingData> data, String model, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(int index, List<Double> embedding) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Long promptTokens, Double cost) {}
}
