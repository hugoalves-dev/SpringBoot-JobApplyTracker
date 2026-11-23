package com.jobtracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Hybrid FTS + pgvector retrieval. Embeddings come from OpenRouter's OpenAI-compatible
 * embeddings endpoint and are independent of the assistant chat provider.
 */
@Component
@ConfigurationProperties(prefix = "app.semantic-search")
public class SemanticSearchProperties {
    private boolean enabled;
    private String baseUrl = "https://openrouter.ai/api/v1";
    private String apiKey;
    private String model = "qwen/qwen3-embedding-8b";
    /** Must match the vector(N) column created by Flyway. */
    private int dimensions = 1024;
    /**
     * When the provider returns more dimensions than requested, keep the first {@code dimensions} and
     * re-normalise. Only valid for Matryoshka-trained models (Qwen3-Embedding is); otherwise the
     * response is rejected.
     */
    private boolean truncateToDimensions = true;
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int batchSize = 16;
    private int maxEmbeddingsPerRun = 64;
    private int maxSourcesScannedPerRun = 500;
    private int maxInputChars = 8000;
    private int maxQueryChars = 500;
    private int maxAttempts = 5;
    private Duration retryBaseDelay = Duration.ofMinutes(1);
    private Duration claimLease = Duration.ofMinutes(5);
    private long schedulerIntervalMs = 60_000;
    private Duration profileRefreshInterval = Duration.ofHours(24);
    private int candidateLimit = 50;
    private int rrfK = 60;
    /** Cosine distance above which a vector-only hit is treated as unrelated. Model-specific; tune per model. */
    private double maxCosineDistance = 0.65;
    /** After a provider failure, interactive searches skip the provider for this long and use FTS only. */
    private Duration providerCooldown = Duration.ofSeconds(60);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public boolean isTruncateToDimensions() { return truncateToDimensions; }
    public void setTruncateToDimensions(boolean value) { this.truncateToDimensions = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxEmbeddingsPerRun() { return maxEmbeddingsPerRun; }
    public void setMaxEmbeddingsPerRun(int value) { this.maxEmbeddingsPerRun = value; }
    public int getMaxSourcesScannedPerRun() { return maxSourcesScannedPerRun; }
    public void setMaxSourcesScannedPerRun(int value) { this.maxSourcesScannedPerRun = value; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    public int getMaxQueryChars() { return maxQueryChars; }
    public void setMaxQueryChars(int maxQueryChars) { this.maxQueryChars = maxQueryChars; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
    public Duration getClaimLease() { return claimLease; }
    public void setClaimLease(Duration claimLease) { this.claimLease = claimLease; }
    public long getSchedulerIntervalMs() { return schedulerIntervalMs; }
    public void setSchedulerIntervalMs(long value) { this.schedulerIntervalMs = value; }
    public Duration getProfileRefreshInterval() { return profileRefreshInterval; }
    public void setProfileRefreshInterval(Duration value) { this.profileRefreshInterval = value; }
    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    public int getRrfK() { return rrfK; }
    public void setRrfK(int rrfK) { this.rrfK = rrfK; }
    public double getMaxCosineDistance() { return maxCosineDistance; }
    public void setMaxCosineDistance(double value) { this.maxCosineDistance = value; }
    public Duration getProviderCooldown() { return providerCooldown; }
    public void setProviderCooldown(Duration providerCooldown) { this.providerCooldown = providerCooldown; }

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }
}
