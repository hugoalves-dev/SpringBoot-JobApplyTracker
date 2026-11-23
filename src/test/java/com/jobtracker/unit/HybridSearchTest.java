package com.jobtracker.unit;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.dto.assistant.AssistantApplicationView;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.assistant.AssistantApplicationQueryRepository;
import com.jobtracker.repository.assistant.AssistantApplicationQueryRepository.ProfileMatchRow;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ProfileDocument;
import com.jobtracker.service.assistant.AssistantApplicationQueryService;
import com.jobtracker.service.assistant.AssistantApplicationQueryService.ProfileMatchResult;
import com.jobtracker.service.semantic.ReciprocalRankFusion;
import com.jobtracker.service.semantic.SemanticSearchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchTest {
    private static final String VECTOR = "[0.1,0.2]";
    private final AssistantApplicationQueryRepository queries = mock(AssistantApplicationQueryRepository.class);
    private final SemanticSearchService semantic = mock(SemanticSearchService.class);
    private final SemanticEmbeddingRepository embeddings = mock(SemanticEmbeddingRepository.class);
    private final SemanticSearchProperties semanticProperties = new SemanticSearchProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID userId = UUID.randomUUID();
    private AssistantApplicationQueryService service;

    private static AssistantApplicationView app(String name, String note) {
        return new AssistantApplicationView(UUID.nameUUIDFromBytes(name.getBytes()), name, "Org", null, "APPLIED",
                null, null, 0, null, note, false, null);
    }

    @BeforeEach
    void setUp() {
        when(semantic.model()).thenReturn("qwen/qwen3-embedding-8b");
        when(semantic.dimensions()).thenReturn(1024);
        service = new AssistantApplicationQueryService(queries, mock(ApplicationRepository.class),
                new AssistantProperties(), semantic, semanticProperties, embeddings, meters);
    }

    @Test
    void semanticDisabledKeepsOriginalFtsQueryUntouched() {
        when(semantic.isQueryAvailable()).thenReturn(false);

        service.search(userId, "PostgreSQL", null, null, null, null, null, false, null);

        verify(queries).search(userId, "PostgreSQL", null, null, null, null, null, false, 10, false);
        verify(semantic, never()).embedQuery(anyString());
        verify(queries, never()).semanticSearch(any(), any(), any(), anyInt(), anyDouble(), any(), any(), any(),
                any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void hybridSearchFusesKeywordAndSemanticResultsForTheSameUserAndFilters() {
        AssistantApplicationView keywordHit = app("RabbitMQ Engineer", "RabbitMQ");
        AssistantApplicationView semanticHit = app("Backend Engineer", "event-driven architecture with Kafka");
        when(semantic.isQueryAvailable()).thenReturn(true);
        when(semantic.embedQuery("RabbitMQ")).thenReturn(Optional.of(VECTOR));
        when(queries.search(userId, "RabbitMQ", "ACME", null, null, null, null, false, 50, false))
                .thenReturn(List.of(keywordHit));
        when(queries.semanticSearch(eq(userId), eq(VECTOR), eq("qwen/qwen3-embedding-8b"), eq(1024), eq(0.65),
                eq("ACME"), isNull(), isNull(), isNull(), isNull(), eq(false), eq(50)))
                .thenReturn(List.of(semanticHit, keywordHit));

        List<AssistantApplicationView> results = service.search(userId, "RabbitMQ", "ACME", null, null,
                null, null, false, null);

        assertThat(results).extracting(AssistantApplicationView::vacancyName)
                .containsExactly("RabbitMQ Engineer", "Backend Engineer");
        assertThat(results).extracting(AssistantApplicationView::matchedBy)
                .containsExactly(ReciprocalRankFusion.BOTH, ReciprocalRankFusion.SEMANTIC);
        assertThat(meters.find("assistant.search.latency").tag("mode", "hybrid").timer().count()).isEqualTo(1);
    }

    @Test
    void providerFailureFallsBackToKeywordResults() {
        when(semantic.isQueryAvailable()).thenReturn(true);
        when(semantic.isIndexingEnabled()).thenReturn(true);
        when(semantic.embedQuery("PostgreSQL")).thenReturn(Optional.empty());
        when(queries.search(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean()))
                .thenReturn(List.of(app("DBA", "PostgreSQL")));

        List<AssistantApplicationView> results = service.search(userId, "PostgreSQL", null, null, null,
                null, null, false, 5);

        assertThat(results).singleElement()
                .satisfies(v -> assertThat(v.matchedBy()).isEqualTo(ReciprocalRankFusion.KEYWORD));
        verify(queries, never()).semanticSearch(any(), any(), any(), anyInt(), anyDouble(), any(), any(), any(),
                any(), any(), anyBoolean(), anyInt());
        assertThat(meters.find("assistant.search.latency").tag("mode", "fts_fallback").timer().count()).isEqualTo(1);
    }

    @Test
    void vectorQueryFailureFallsBackToKeywordResults() {
        when(semantic.isQueryAvailable()).thenReturn(true);
        when(semantic.embedQuery("PostgreSQL")).thenReturn(Optional.of(VECTOR));
        when(queries.search(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean()))
                .thenReturn(List.of(app("DBA", "PostgreSQL")));
        when(queries.semanticSearch(any(), any(), any(), anyInt(), anyDouble(), any(), any(), any(), any(), any(),
                anyBoolean(), anyInt())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(service.search(userId, "PostgreSQL", null, null, null, null, null, false, 5))
                .extracting(AssistantApplicationView::vacancyName).containsExactly("DBA");
    }

    @Test
    void shortTokensStayOnTheLiteralFallbackWithoutEmbedding() {
        when(semantic.isQueryAvailable()).thenReturn(true);

        service.search(userId, "Go", null, null, null, null, null, false, null);

        verify(queries).search(userId, "Go", null, null, null, null, null, false, 10, true);
        verify(semantic, never()).embedQuery(anyString());
    }

    @Test
    void profileMatchReportsMissingProfileEmbedding() {
        when(semantic.isIndexingEnabled()).thenReturn(true);
        when(embeddings.findReadyProfileDocuments(eq(userId), any(), anyInt(), isNull(), anyInt())).thenReturn(List.of());

        ProfileMatchResult result = service.matchProfile(userId, null, List.of("Java"), false, null, null, null,
                null, null, false, null);

        assertThat(result.status()).isEqualTo("NO_PROFILE_EMBEDDING");
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void profileMatchReportsDisabledSemanticSearch() {
        when(semantic.isIndexingEnabled()).thenReturn(false);

        assertThat(service.matchProfile(userId, null, null, false, null, null, null, null, null, false, null)
                .status()).isEqualTo("SEMANTIC_SEARCH_DISABLED");
    }

    @Test
    void profileMatchChecksRequirementsExplicitlyAndCanDropPartialMatches() {
        when(semantic.isIndexingEnabled()).thenReturn(true);
        when(embeddings.findReadyProfileDocuments(eq(userId), any(), anyInt(), isNull(), anyInt()))
                .thenReturn(List.of(new ProfileDocument(UUID.randomUUID(), "CV", "Java developer, 8 years")));
        AssistantApplicationView closest = app("Senior Kotlin Engineer", null);
        AssistantApplicationView second = app("Senior Java Engineer", null);
        when(queries.profileMatches(eq(userId), isNull(), any(), anyInt(), any(), any(), any(), any(), any(),
                anyBoolean(), anyInt())).thenReturn(List.of(
                new ProfileMatchRow(closest, "On-site in São Paulo"),
                new ProfileMatchRow(second, "Remote, PostgreSQL")));

        ProfileMatchResult all = service.matchProfile(userId, null, List.of("Java", "Remote"), false,
                null, null, null, null, null, false, null);
        ProfileMatchResult strict = service.matchProfile(userId, null, List.of("Java", "Remote"), true,
                null, null, null, null, null, false, null);

        assertThat(all.matches()).hasSize(2);
        assertThat(all.matches().getFirst().requirementChecks()).noneMatch(c -> c.present());
        assertThat(strict.matches()).singleElement().satisfies(m -> {
            assertThat(m.application().vacancyName()).isEqualTo("Senior Java Engineer");
            assertThat(m.semanticRank()).isEqualTo(2);
            assertThat(m.requirementChecks()).allMatch(c -> c.present());
        });
        assertThat(strict.guidance()).contains("never be shown as a percentage");
        assertThat(strict.profileDocuments()).singleElement()
                .satisfies(d -> assertThat(d.excerpt()).contains("Java developer"));
    }
}
