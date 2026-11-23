package com.jobtracker.service.semantic;

import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gatekeeper for the vector half of hybrid search. Every failure path degrades to FTS-only instead of
 * failing the request: disabled config, incompatible vector column, or an unhealthy provider.
 */
@Service
public class SemanticSearchService {
    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final int UNCHECKED = Integer.MIN_VALUE;

    private final SemanticSearchProperties properties;
    private final OpenRouterEmbeddingClient client;
    private final SemanticEmbeddingRepository repository;
    private final Clock clock;
    private final AtomicLong providerUnavailableUntil = new AtomicLong();
    private volatile int columnDimensions = UNCHECKED;

    @Autowired
    public SemanticSearchService(SemanticSearchProperties properties, OpenRouterEmbeddingClient client,
                                 SemanticEmbeddingRepository repository) {
        this(properties, client, repository, Clock.systemUTC());
    }

    /** Explicit wiring for tests. */
    public SemanticSearchService(SemanticSearchProperties properties, OpenRouterEmbeddingClient client,
                          SemanticEmbeddingRepository repository, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.repository = repository;
        this.clock = clock;
    }

    /** Config + schema are usable. Provider health is checked separately so indexing keeps retrying. */
    public boolean isIndexingEnabled() {
        return properties.isConfigured() && schemaCompatible();
    }

    public boolean isQueryAvailable() {
        return isIndexingEnabled() && clock.millis() >= providerUnavailableUntil.get();
    }

    /** Embeds a search query, or returns empty (and opens the cooldown) so the caller falls back to FTS. */
    public Optional<String> embedQuery(String query) {
        if (!isQueryAvailable() || query == null || query.isBlank()) return Optional.empty();
        String bounded = query.length() > properties.getMaxQueryChars()
                ? query.substring(0, properties.getMaxQueryChars()) : query;
        try {
            float[] vector = client.embed(List.of(bounded), "query").vectors().getFirst();
            return Optional.of(VectorLiterals.toLiteral(vector));
        } catch (RuntimeException ex) {
            providerUnavailableUntil.set(clock.millis() + properties.getProviderCooldown().toMillis());
            log.warn("event=SEMANTIC_QUERY_EMBEDDING_FAILED fallback=fts cooldown={} reason={}",
                    properties.getProviderCooldown(), ex.getMessage());
            return Optional.empty();
        }
    }

    public String model() { return properties.getModel(); }

    public int dimensions() { return properties.getDimensions(); }

    private boolean schemaCompatible() {
        int dims = columnDimensions;
        if (dims == UNCHECKED) {
            try {
                dims = repository.columnDimensions();
            } catch (RuntimeException ex) {
                log.warn("event=SEMANTIC_SCHEMA_CHECK_FAILED reason={}", ex.getMessage());
                return false;
            }
            columnDimensions = dims;
            if (dims != properties.getDimensions()) {
                log.error("event=SEMANTIC_SEARCH_DISABLED reason=dimension_mismatch column={} configured={} " +
                                "action='add a Flyway migration altering semantic_embeddings.embedding'",
                        dims, properties.getDimensions());
            }
        }
        return dims == properties.getDimensions();
    }
}
