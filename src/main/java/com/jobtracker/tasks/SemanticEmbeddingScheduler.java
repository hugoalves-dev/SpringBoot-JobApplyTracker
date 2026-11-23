package com.jobtracker.tasks;

import com.jobtracker.service.semantic.EmbeddingSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.semantic-search", name = "enabled", havingValue = "true")
public class SemanticEmbeddingScheduler {
    private static final Logger log = LoggerFactory.getLogger(SemanticEmbeddingScheduler.class);

    private final EmbeddingSyncService sync;

    public SemanticEmbeddingScheduler(EmbeddingSyncService sync) {
        this.sync = sync;
    }

    @Scheduled(fixedDelayString = "${app.semantic-search.scheduler-interval-ms:60000}",
            initialDelayString = "${app.semantic-search.scheduler-initial-delay-ms:30000}")
    public void run() {
        try {
            EmbeddingSyncService.RunResult result = sync.runOnce();
            if (result.enqueued() > 0 || result.embedded() > 0) {
                log.info("event=SEMANTIC_EMBEDDING_SYNC enqueued={} embedded={}", result.enqueued(), result.embedded());
            }
        } catch (RuntimeException ex) {
            log.error("event=SEMANTIC_EMBEDDING_SYNC_FAILED reason={}", ex.getMessage(), ex);
        }
    }
}
