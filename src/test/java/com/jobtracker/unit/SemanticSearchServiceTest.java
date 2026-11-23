package com.jobtracker.unit;

import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import com.jobtracker.service.semantic.EmbeddingProviderException;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient.EmbeddingBatch;
import com.jobtracker.service.semantic.SemanticSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticSearchServiceTest {
    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private final OpenRouterEmbeddingClient client = mock(OpenRouterEmbeddingClient.class);
    private final SemanticEmbeddingRepository repository = mock(SemanticEmbeddingRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T12:00:00Z"));
    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("key");
        properties.setDimensions(2);
        properties.setMaxQueryChars(5);
        when(repository.columnDimensions()).thenReturn(2);
        service = new SemanticSearchService(properties, client, repository, clock);
    }

    @Test
    void embedsBoundedQueryAsPgvectorLiteral() {
        when(client.embed(List.of("Kafka"), "query")).thenReturn(new EmbeddingBatch(List.of(new float[]{0.6f, 0.8f}), 1, null));

        assertThat(service.embedQuery("Kafka streams")).contains("[0.6,0.8]");
    }

    @Test
    void providerFailureOpensCooldownThenRecovers() {
        when(client.embed(any(), any())).thenThrow(new EmbeddingProviderException("down"));

        assertThat(service.embedQuery("x")).isEmpty();
        assertThat(service.isQueryAvailable()).isFalse();
        assertThat(service.isIndexingEnabled()).isTrue();
        assertThat(service.embedQuery("x")).isEmpty();
        verify(client, times(1)).embed(any(), any());

        clock.advance(properties.getProviderCooldown().plusSeconds(1));
        assertThat(service.isQueryAvailable()).isTrue();
    }

    @Test
    void dimensionMismatchWithColumnDisablesSemanticSearch() {
        when(repository.columnDimensions()).thenReturn(1024);

        assertThat(service.isIndexingEnabled()).isFalse();
        assertThat(service.embedQuery("x")).isEmpty();
    }

    @Test
    void missingApiKeyDisablesSemanticSearch() {
        properties.setApiKey(" ");
        assertThat(service.isIndexingEnabled()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
