package com.jobtracker.unit;

import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.entity.enums.BaseInformationDocType;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ApplicationSource;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ClaimedEmbedding;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ProfileSource;
import com.jobtracker.service.BaseInformationService;
import com.jobtracker.service.semantic.EmbeddingContent;
import com.jobtracker.service.semantic.EmbeddingProviderException;
import com.jobtracker.service.semantic.EmbeddingSyncService;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient.EmbeddingBatch;
import com.jobtracker.service.semantic.SemanticSearchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingSyncServiceTest {
    private static final String MODEL = "qwen/qwen3-embedding-8b";
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private final SemanticSearchService semantic = mock(SemanticSearchService.class);
    private final SemanticEmbeddingRepository repository = mock(SemanticEmbeddingRepository.class);
    private final OpenRouterEmbeddingClient client = mock(OpenRouterEmbeddingClient.class);
    private final BaseInformationService baseInformation = mock(BaseInformationService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID userId = UUID.randomUUID();
    private EmbeddingSyncService sync;

    @BeforeEach
    void setUp() {
        properties.setDimensions(3);
        when(semantic.isIndexingEnabled()).thenReturn(true);
        sync = new EmbeddingSyncService(properties, semantic, repository, client, baseInformation, meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ApplicationSource application(UUID embeddingId, String hash, String model, Integer dims, String note) {
        return new ApplicationSource(UUID.randomUUID(), userId, "Backend Engineer", "ACME", note,
                LocalDateTime.of(2026, 11, 20, 10, 0), embeddingId, hash, model, dims);
    }

    @Test
    void disabledIndexingDoesNothing() {
        when(semantic.isIndexingEnabled()).thenReturn(false);

        assertThat(sync.runOnce().ran()).isFalse();
        verifyNoInteractions(repository, client, baseInformation);
    }

    @Test
    void unchangedContentIsNotReEmbedded() {
        String hash = EmbeddingContent.sha256(EmbeddingContent.forApplication("Backend Engineer", "ACME", "Kafka", 8000));
        ApplicationSource source = application(UUID.randomUUID(), hash, MODEL, 3, "Kafka");
        when(repository.findApplicationsNeedingSync(MODEL, 3, 500)).thenReturn(List.of(source));

        assertThat(sync.runOnce().enqueued()).isZero();

        verify(repository).markSourceSeen(source.embeddingId(), source.updatedAt());
        verify(repository, never()).upsertPending(any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void newOrChangedContentIsQueuedWithItsOwnUser() {
        ApplicationSource fresh = application(null, null, null, null, "Kafka");
        ApplicationSource changed = application(UUID.randomUUID(), "old-hash", MODEL, 3, "RabbitMQ");
        when(repository.findApplicationsNeedingSync(MODEL, 3, 500)).thenReturn(List.of(fresh, changed));

        assertThat(sync.runOnce().enqueued()).isEqualTo(2);

        verify(repository).upsertPending(eq("JOB_APPLICATION"), eq(fresh.applicationId()), eq(userId),
                eq("Vacancy: Backend Engineer\nOrganization: ACME\nNotes: Kafka"), anyString(), eq("PENDING"),
                eq(MODEL), eq(3), eq(fresh.updatedAt()));
        verify(repository).upsertPending(eq("JOB_APPLICATION"), eq(changed.applicationId()), eq(userId),
                anyString(), anyString(), eq("PENDING"), eq(MODEL), eq(3), any());
    }

    @Test
    void modelChangeReEmbedsEvenWhenContentIsIdentical() {
        String hash = EmbeddingContent.sha256(EmbeddingContent.forApplication("Backend Engineer", "ACME", "Kafka", 8000));
        when(repository.findApplicationsNeedingSync(MODEL, 3, 500))
                .thenReturn(List.of(application(UUID.randomUUID(), hash, "openai/text-embedding-3-small", 3, "Kafka")));

        assertThat(sync.runOnce().enqueued()).isEqualTo(1);
    }

    @Test
    void emptyContentIsParkedInsteadOfEmbedded() {
        ApplicationSource empty = new ApplicationSource(UUID.randomUUID(), userId, null, " ", null,
                LocalDateTime.now(), null, null, null, null);
        when(repository.findApplicationsNeedingSync(MODEL, 3, 500)).thenReturn(List.of(empty));

        sync.runOnce();

        verify(repository).upsertPending(eq("JOB_APPLICATION"), eq(empty.applicationId()), eq(userId), eq(""),
                anyString(), eq("EMPTY"), eq(MODEL), eq(3), any());
    }

    @Test
    void claimedRowsAreEmbeddedInBatchesAndStoredOnlyForTheirHash() {
        ClaimedEmbedding a = new ClaimedEmbedding(UUID.randomUUID(), "h1", "text one", 1);
        ClaimedEmbedding b = new ClaimedEmbedding(UUID.randomUUID(), "h2", "text two", 1);
        when(repository.claimDue(eq(MODEL), eq(3), eq(5), anyLong(), eq(16))).thenReturn(List.of(a, b), List.of());
        when(client.embed(List.of("text one", "text two"), "document")).thenReturn(new EmbeddingBatch(
                List.of(new float[]{1, 0, 0}, new float[]{0, 1, 0}), 12, 0.0001));
        when(repository.markReady(a.id(), "h1", MODEL, 3, "[1.0,0.0,0.0]")).thenReturn(true);
        when(repository.markReady(b.id(), "h2", MODEL, 3, "[0.0,1.0,0.0]")).thenReturn(false);

        assertThat(sync.runOnce().embedded()).isEqualTo(1);
        assertThat(meters.counter("semantic.embeddings.failures", "stage", "stale_result").count()).isEqualTo(1);
    }

    @Test
    void providerFailureSchedulesRetryWithExponentialBackoff() {
        ClaimedEmbedding row = new ClaimedEmbedding(UUID.randomUUID(), "h1", "text", 3);
        when(repository.claimDue(any(), anyInt(), anyInt(), anyLong(), anyInt())).thenReturn(List.of(row), List.of());
        when(client.embed(any(), eq("document"))).thenThrow(new EmbeddingProviderException("503"));

        assertThat(sync.runOnce().embedded()).isZero();

        verify(repository).markFailed(row.id(), "h1", "503", NOW.plus(Duration.ofMinutes(4)));
        assertThat(meters.counter("semantic.embeddings.failures", "stage", "provider").count()).isEqualTo(1);
    }

    @Test
    void runRespectsPerRunEmbeddingBudget() {
        properties.setMaxEmbeddingsPerRun(3);
        properties.setBatchSize(2);
        ClaimedEmbedding row = new ClaimedEmbedding(UUID.randomUUID(), "h", "t", 1);
        when(repository.claimDue(any(), anyInt(), anyInt(), anyLong(), eq(2))).thenReturn(List.of(row, row));
        when(repository.claimDue(any(), anyInt(), anyInt(), anyLong(), eq(1))).thenReturn(List.of(row));
        when(client.embed(any(), any())).thenAnswer(inv -> new EmbeddingBatch(
                ((List<?>) inv.getArgument(0)).stream().map(x -> new float[]{1, 0, 0}).toList(), 1, null));
        when(repository.markReady(any(), any(), any(), anyInt(), any())).thenReturn(true);

        assertThat(sync.runOnce().embedded()).isEqualTo(3);
    }

    @Test
    void profileDocumentReadFailureIsRecordedWithoutCallingTheProvider() {
        ProfileSource source = new ProfileSource(UUID.randomUUID(), userId, "GOOGLE_DOC", "file-1",
                null, null, null, null, 0);
        when(repository.findProfileDocumentsNeedingSync(eq(MODEL), eq(3), any(), eq(500))).thenReturn(List.of(source));
        when(baseInformation.readContent(userId, BaseInformationDocType.GOOGLE_DOC, "file-1"))
                .thenThrow(new IllegalStateException("reauthorization required"));

        sync.runOnce();

        verify(repository).markProfileSourceError(source.baseInformationId(), userId, "reauthorization required",
                NOW.plus(Duration.ofMinutes(1)), MODEL, 3);
        verify(client, never()).embed(any(), any());
    }

    @Test
    void profileDocumentIsReadWithItsOwnersCredentialsAndQueued() {
        ProfileSource source = new ProfileSource(UUID.randomUUID(), userId, "PDF", "file-2",
                null, null, null, null, 0);
        when(repository.findProfileDocumentsNeedingSync(eq(MODEL), eq(3), eq(NOW.minus(Duration.ofHours(24))), eq(500)))
                .thenReturn(List.of(source));
        when(baseInformation.readContent(userId, BaseInformationDocType.PDF, "file-2"))
                .thenReturn("  Java, Spring, PostgreSQL  ");

        sync.runOnce();

        verify(repository).upsertPending(eq("PROFILE_DOCUMENT"), eq(source.baseInformationId()), eq(userId),
                eq("Java, Spring, PostgreSQL"), anyString(), eq("PENDING"), eq(MODEL), eq(3), eq(null));
    }
}
