package com.jobtracker.service.semantic;

import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.entity.enums.BaseInformationDocType;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ApplicationSource;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ClaimedEmbedding;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.Coverage;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ProfileSource;
import com.jobtracker.service.BaseInformationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.jobtracker.repository.semantic.SemanticEmbeddingRepository.JOB_APPLICATION;
import static com.jobtracker.repository.semantic.SemanticEmbeddingRepository.PROFILE_DOCUMENT;

/**
 * Reconciles {@code semantic_embeddings} with its sources. One polling loop covers backfill (sources
 * without rows), change detection (content hash), model/space switches, and retries, so there is no
 * coupling to the write path of applications or Drive documents and no event that can be lost.
 * Each statement commits on its own: no transaction is held open across provider calls.
 */
@Service
public class EmbeddingSyncService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingSyncService.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);

    private final SemanticSearchProperties properties;
    private final SemanticSearchService semantic;
    private final SemanticEmbeddingRepository repository;
    private final OpenRouterEmbeddingClient client;
    private final BaseInformationService baseInformation;
    private final MeterRegistry meters;
    private final Clock clock;
    private final AtomicReference<Coverage> coverage = new AtomicReference<>();

    @Autowired
    public EmbeddingSyncService(SemanticSearchProperties properties, SemanticSearchService semantic,
                                SemanticEmbeddingRepository repository, OpenRouterEmbeddingClient client,
                                BaseInformationService baseInformation, MeterRegistry meters) {
        this(properties, semantic, repository, client, baseInformation, meters, Clock.systemUTC());
    }

    /** Explicit wiring for tests. */
    public EmbeddingSyncService(SemanticSearchProperties properties, SemanticSearchService semantic,
                         SemanticEmbeddingRepository repository, OpenRouterEmbeddingClient client,
                         BaseInformationService baseInformation, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.semantic = semantic;
        this.repository = repository;
        this.client = client;
        this.baseInformation = baseInformation;
        this.meters = meters;
        this.clock = clock;
        Gauge.builder("semantic.embeddings.coverage", coverage, ref -> value(ref, Coverage::applicationRatio))
                .description("Share of non-empty applications with a READY embedding for the active model")
                .register(meters);
        Gauge.builder("semantic.embeddings.backlog", coverage, ref -> value(ref, c -> (double) c.pending()))
                .register(meters);
        Gauge.builder("semantic.embeddings.exhausted", coverage, ref -> value(ref, c -> (double) c.exhausted()))
                .description("Rows that hit max attempts; they retry only when their content changes")
                .register(meters);
        Gauge.builder("semantic.embeddings.source_errors", coverage, ref -> value(ref, c -> (double) c.sourceErrors()))
                .register(meters);
    }

    public RunResult runOnce() {
        if (!semantic.isIndexingEnabled()) return RunResult.SKIPPED;
        int enqueued = syncApplications() + syncProfileDocuments();
        int embedded = processDue();
        try {
            coverage.set(repository.coverage(properties.getModel(), properties.getDimensions(),
                    properties.getMaxAttempts()));
        } catch (RuntimeException ex) {
            log.warn("event=SEMANTIC_COVERAGE_REFRESH_FAILED reason={}", ex.getMessage());
        }
        return new RunResult(true, enqueued, embedded);
    }

    int syncApplications() {
        List<ApplicationSource> sources = repository.findApplicationsNeedingSync(
                properties.getModel(), properties.getDimensions(), properties.getMaxSourcesScannedPerRun());
        int enqueued = 0;
        for (ApplicationSource source : sources) {
            String content = EmbeddingContent.forApplication(source.vacancyName(), source.organization(),
                    source.note(), properties.getMaxInputChars());
            String hash = EmbeddingContent.sha256(content);
            if (unchanged(source.embeddingId(), source.contentHash(), source.model(), source.dimensions(), hash)) {
                repository.markSourceSeen(source.embeddingId(), source.updatedAt());
                continue;
            }
            repository.upsertPending(JOB_APPLICATION, source.applicationId(), source.userId(), content, hash,
                    content.isEmpty() ? "EMPTY" : "PENDING", properties.getModel(), properties.getDimensions(),
                    source.updatedAt());
            enqueued++;
        }
        return enqueued;
    }

    int syncProfileDocuments() {
        Instant now = clock.instant();
        List<ProfileSource> sources = repository.findProfileDocumentsNeedingSync(properties.getModel(),
                properties.getDimensions(), now.minus(properties.getProfileRefreshInterval()),
                properties.getMaxSourcesScannedPerRun());
        int enqueued = 0;
        for (ProfileSource source : sources) {
            String text;
            try {
                text = baseInformation.readContent(source.userId(),
                        BaseInformationDocType.valueOf(source.docType()), source.googleFileId());
            } catch (RuntimeException ex) {
                meters.counter("semantic.embeddings.failures", "stage", "source_read").increment();
                log.warn("event=SEMANTIC_PROFILE_READ_FAILED baseInformationId={} reason={}",
                        source.baseInformationId(), ex.getMessage());
                Duration delay = min(backoff(source.attempts() + 1), properties.getProfileRefreshInterval());
                repository.markProfileSourceError(source.baseInformationId(), source.userId(), ex.getMessage(),
                        now.plus(delay), properties.getModel(), properties.getDimensions());
                continue;
            }
            String content = EmbeddingContent.forProfileDocument(text, properties.getMaxInputChars());
            String hash = EmbeddingContent.sha256(content);
            if (unchanged(source.embeddingId(), source.contentHash(), source.model(), source.dimensions(), hash)) {
                repository.markSourceSeen(source.embeddingId(), null);
                continue;
            }
            repository.upsertPending(PROFILE_DOCUMENT, source.baseInformationId(), source.userId(), content, hash,
                    content.isEmpty() ? "EMPTY" : "PENDING", properties.getModel(), properties.getDimensions(), null);
            enqueued++;
        }
        return enqueued;
    }

    int processDue() {
        int budget = properties.getMaxEmbeddingsPerRun();
        int embedded = 0;
        while (budget > 0) {
            List<ClaimedEmbedding> claimed = repository.claimDue(properties.getModel(), properties.getDimensions(),
                    properties.getMaxAttempts(), properties.getClaimLease().toSeconds(),
                    Math.min(budget, properties.getBatchSize()));
            if (claimed.isEmpty()) break;
            budget -= claimed.size();
            embedded += embedBatch(claimed);
        }
        return embedded;
    }

    private int embedBatch(List<ClaimedEmbedding> claimed) {
        List<float[]> vectors;
        try {
            vectors = client.embed(claimed.stream().map(ClaimedEmbedding::content).toList(), "document").vectors();
        } catch (RuntimeException ex) {
            meters.counter("semantic.embeddings.failures", "stage", "provider").increment(claimed.size());
            log.warn("event=SEMANTIC_EMBEDDING_BATCH_FAILED size={} reason={}", claimed.size(), ex.getMessage());
            for (ClaimedEmbedding row : claimed) {
                repository.markFailed(row.id(), row.contentHash(), ex.getMessage(),
                        clock.instant().plus(backoff(row.attempts())));
            }
            return 0;
        }
        int stored = 0;
        for (int i = 0; i < claimed.size(); i++) {
            ClaimedEmbedding row = claimed.get(i);
            boolean ready = repository.markReady(row.id(), row.contentHash(), properties.getModel(),
                    properties.getDimensions(), VectorLiterals.toLiteral(vectors.get(i)));
            if (ready) {
                stored++;
            } else {
                // Content or model changed while the call was in flight; the newer row will be embedded on its own.
                meters.counter("semantic.embeddings.failures", "stage", "stale_result").increment();
            }
        }
        return stored;
    }

    Duration backoff(int attempts) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = properties.getRetryBaseDelay().multipliedBy(factor);
        return min(delay, MAX_RETRY_DELAY);
    }

    private boolean unchanged(Object embeddingId, String storedHash, String storedModel, Integer storedDimensions,
                              String hash) {
        return embeddingId != null && hash.equals(storedHash) && properties.getModel().equals(storedModel)
                && storedDimensions != null && storedDimensions == properties.getDimensions();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static double value(AtomicReference<Coverage> ref, java.util.function.ToDoubleFunction<Coverage> f) {
        Coverage c = ref.get();
        return c == null ? Double.NaN : f.applyAsDouble(c);
    }

    public record RunResult(boolean ran, int enqueued, int embedded) {
        static final RunResult SKIPPED = new RunResult(false, 0, 0);
    }
}
