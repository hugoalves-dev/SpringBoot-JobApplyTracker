package com.jobtracker.repository.semantic;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * JDBC access to {@code semantic_embeddings}. Kept out of JPA on purpose: Hibernate has no native
 * pgvector type and the table is only ever touched through explicit, user-scoped SQL.
 */
@Repository
public class SemanticEmbeddingRepository {
    public static final String JOB_APPLICATION = "JOB_APPLICATION";
    public static final String PROFILE_DOCUMENT = "PROFILE_DOCUMENT";

    private final NamedParameterJdbcTemplate jdbc;

    public SemanticEmbeddingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applications with no embedding row, a newer source row, or an embedding from another model/space. */
    public List<ApplicationSource> findApplicationsNeedingSync(String model, int dimensions, int limit) {
        String sql = """
                SELECT a.id, a.user_id, a.vacancy_name, a.organization, a.note, a.updated_at,
                       e.id AS embedding_id, e.content_hash, e.model, e.dimensions
                FROM job_applications a
                LEFT JOIN semantic_embeddings e ON e.job_application_id = a.id
                WHERE e.id IS NULL
                   OR e.source_updated_at IS DISTINCT FROM a.updated_at
                   OR e.model <> :model OR e.dimensions <> :dimensions
                ORDER BY a.updated_at ASC, a.id ASC
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("model", model).addValue("dimensions", dimensions).addValue("limit", limit),
                (rs, row) -> new ApplicationSource(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("vacancy_name"), rs.getString("organization"), rs.getString("note"),
                        rs.getTimestamp("updated_at").toLocalDateTime(),
                        rs.getObject("embedding_id", UUID.class), rs.getString("content_hash"),
                        rs.getString("model"), (Integer) rs.getObject("dimensions")));
    }

    /** Profile documents never synced, due for a periodic re-read, retrying a Drive error, or from another model. */
    public List<ProfileSource> findProfileDocumentsNeedingSync(String model, int dimensions, Instant refreshBefore,
                                                               int limit) {
        String sql = """
                SELECT b.id, c.user_id, b.doc_type, b.google_file_id,
                       e.id AS embedding_id, e.content_hash, e.model, e.dimensions, e.attempts
                FROM google_drive_base_information b
                JOIN google_drive_connections c ON c.id = b.connection_id
                LEFT JOIN semantic_embeddings e ON e.base_information_id = b.id
                WHERE e.id IS NULL
                   OR e.model <> :model OR e.dimensions <> :dimensions
                   OR (e.status = 'SOURCE_ERROR' AND e.next_attempt_at <= now())
                   OR (e.status <> 'SOURCE_ERROR' AND (e.source_synced_at IS NULL OR e.source_synced_at < :refreshBefore))
                ORDER BY e.source_synced_at ASC NULLS FIRST, b.id ASC
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("model", model).addValue("dimensions", dimensions)
                        .addValue("refreshBefore", OffsetDateTime.ofInstant(refreshBefore, ZoneOffset.UTC))
                        .addValue("limit", limit),
                (rs, row) -> new ProfileSource(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("doc_type"), rs.getString("google_file_id"),
                        rs.getObject("embedding_id", UUID.class), rs.getString("content_hash"),
                        rs.getString("model"), (Integer) rs.getObject("dimensions"),
                        rs.getObject("attempts") == null ? 0 : rs.getInt("attempts")));
    }

    /** Inserts or resets an embedding row; the old vector is dropped because it no longer describes the content. */
    public void upsertPending(String sourceType, UUID sourceId, UUID userId, String content, String contentHash,
                              String status, String model, int dimensions, LocalDateTime sourceUpdatedAt) {
        String conflictColumn = conflictColumn(sourceType);
        String sql = """
                INSERT INTO semantic_embeddings (id, user_id, source_type, %1$s, content, content_hash, model,
                    dimensions, embedding, status, attempts, last_error, next_attempt_at, source_updated_at,
                    source_synced_at, embedded_at, created_at, updated_at)
                VALUES (:id, :userId, :sourceType, :sourceId, :content, :contentHash, :model, :dimensions, NULL,
                    :status, 0, NULL, now(), :sourceUpdatedAt, now(), NULL, now(), now())
                ON CONFLICT (%1$s) DO UPDATE SET
                    user_id = EXCLUDED.user_id, content = EXCLUDED.content, content_hash = EXCLUDED.content_hash,
                    model = EXCLUDED.model, dimensions = EXCLUDED.dimensions, embedding = NULL,
                    status = EXCLUDED.status, attempts = 0, last_error = NULL, next_attempt_at = now(),
                    source_updated_at = EXCLUDED.source_updated_at, source_synced_at = now(),
                    embedded_at = NULL, updated_at = now()
                """.formatted(conflictColumn);
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("userId", userId).addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId).addValue("content", content).addValue("contentHash", contentHash)
                .addValue("model", model).addValue("dimensions", dimensions).addValue("status", status)
                .addValue("sourceUpdatedAt", sourceUpdatedAt == null ? null : Timestamp.valueOf(sourceUpdatedAt)));
    }

    /** Source row changed but the embedded text did not (e.g. a status change): just record that we saw it. */
    public void markSourceSeen(UUID embeddingId, LocalDateTime sourceUpdatedAt) {
        jdbc.update("""
                UPDATE semantic_embeddings
                SET source_updated_at = :sourceUpdatedAt, source_synced_at = now(), updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", embeddingId)
                .addValue("sourceUpdatedAt", sourceUpdatedAt == null ? null : Timestamp.valueOf(sourceUpdatedAt)));
    }

    /**
     * Records a failure to read a profile document from Drive. An existing READY vector from the current
     * model is kept (stale-but-valid beats nothing); otherwise the row is parked as SOURCE_ERROR.
     */
    public void markProfileSourceError(UUID baseInformationId, UUID userId, String error, Instant nextAttemptAt,
                                       String model, int dimensions) {
        jdbc.update("""
                INSERT INTO semantic_embeddings (id, user_id, source_type, base_information_id, content, content_hash,
                    model, dimensions, status, attempts, last_error, next_attempt_at, source_synced_at,
                    created_at, updated_at)
                VALUES (:id, :userId, 'PROFILE_DOCUMENT', :sourceId, '', '', :model, :dimensions, 'SOURCE_ERROR', 1,
                    :error, :nextAttemptAt, now(), now(), now())
                ON CONFLICT (base_information_id) DO UPDATE SET
                    status = CASE WHEN semantic_embeddings.status = 'READY' AND semantic_embeddings.model = :model
                                       AND semantic_embeddings.dimensions = :dimensions
                                  THEN 'READY' ELSE 'SOURCE_ERROR' END,
                    embedding = CASE WHEN semantic_embeddings.model = :model AND semantic_embeddings.dimensions = :dimensions
                                     THEN semantic_embeddings.embedding ELSE NULL END,
                    model = :model, dimensions = :dimensions,
                    attempts = semantic_embeddings.attempts + 1, last_error = :error,
                    next_attempt_at = :nextAttemptAt, source_synced_at = now(), updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("userId", userId).addValue("sourceId", baseInformationId)
                .addValue("model", model).addValue("dimensions", dimensions).addValue("error", truncate(error))
                .addValue("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC)));
    }

    /**
     * Atomically leases due rows (PENDING, or FAILED with attempts left) so concurrent instances never
     * embed the same row twice. The lease pushes next_attempt_at forward; a crashed worker's rows become
     * due again once it expires.
     */
    public List<ClaimedEmbedding> claimDue(String model, int dimensions, int maxAttempts, long leaseSeconds, int limit) {
        String sql = """
                UPDATE semantic_embeddings
                SET attempts = attempts + 1,
                    next_attempt_at = now() + make_interval(secs => :leaseSeconds),
                    updated_at = now()
                WHERE id IN (
                    SELECT id FROM semantic_embeddings
                    WHERE status IN ('PENDING', 'FAILED') AND attempts < :maxAttempts
                      AND next_attempt_at <= now() AND model = :model AND dimensions = :dimensions
                    ORDER BY next_attempt_at ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, content_hash, content, attempts
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("model", model).addValue("dimensions", dimensions)
                        .addValue("maxAttempts", maxAttempts).addValue("leaseSeconds", (double) leaseSeconds)
                        .addValue("limit", limit),
                (rs, row) -> new ClaimedEmbedding(rs.getObject("id", UUID.class), rs.getString("content_hash"),
                        rs.getString("content"), rs.getInt("attempts")));
    }

    /** Stores the vector only if the row still holds the content/model it was computed for. */
    public boolean markReady(UUID id, String contentHash, String model, int dimensions, String vectorLiteral) {
        return jdbc.update("""
                UPDATE semantic_embeddings
                SET embedding = CAST(:vector AS vector), status = 'READY', last_error = NULL,
                    embedded_at = now(), updated_at = now()
                WHERE id = :id AND content_hash = :contentHash AND model = :model AND dimensions = :dimensions
                """, new MapSqlParameterSource().addValue("id", id).addValue("contentHash", contentHash)
                .addValue("model", model).addValue("dimensions", dimensions).addValue("vector", vectorLiteral)) == 1;
    }

    public void markFailed(UUID id, String contentHash, String error, Instant nextAttemptAt) {
        jdbc.update("""
                UPDATE semantic_embeddings
                SET status = 'FAILED', last_error = :error, next_attempt_at = :nextAttemptAt, updated_at = now()
                WHERE id = :id AND content_hash = :contentHash
                """, new MapSqlParameterSource().addValue("id", id).addValue("contentHash", contentHash)
                .addValue("error", truncate(error))
                .addValue("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC)));
    }

    public Coverage coverage(String model, int dimensions, int maxAttempts) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM job_applications) AS applications,
                       count(*) FILTER (WHERE source_type = 'JOB_APPLICATION' AND status = 'READY'
                                        AND model = :model AND dimensions = :dimensions) AS ready_applications,
                       count(*) FILTER (WHERE source_type = 'JOB_APPLICATION' AND status = 'EMPTY') AS empty_applications,
                       count(*) FILTER (WHERE status IN ('PENDING', 'FAILED') AND attempts < :maxAttempts) AS pending,
                       count(*) FILTER (WHERE status = 'FAILED' AND attempts >= :maxAttempts) AS exhausted,
                       count(*) FILTER (WHERE status = 'SOURCE_ERROR') AS source_errors
                FROM semantic_embeddings
                """, new MapSqlParameterSource().addValue("model", model).addValue("dimensions", dimensions)
                        .addValue("maxAttempts", maxAttempts),
                (rs, row) -> new Coverage(rs.getLong("applications"), rs.getLong("ready_applications"),
                        rs.getLong("empty_applications"), rs.getLong("pending"), rs.getLong("exhausted"),
                        rs.getLong("source_errors")));
    }

    /** Declared dimension of the vector column, or -1 when pgvector/the table is absent. */
    public int columnDimensions() {
        List<Integer> result = jdbc.query("""
                SELECT a.atttypmod FROM pg_attribute a
                WHERE a.attrelid = to_regclass('semantic_embeddings') AND a.attname = 'embedding' AND NOT a.attisdropped
                """, new MapSqlParameterSource(), (rs, row) -> rs.getInt(1));
        return result.isEmpty() ? -1 : result.getFirst();
    }

    /** The user's embedded profile documents for the active model, optionally narrowed to one document. */
    public List<ProfileDocument> findReadyProfileDocuments(UUID userId, String model, int dimensions,
                                                           UUID baseInformationId, int excerptChars) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.base_information_id, b.document_name, LEFT(e.content, :excerptChars) AS excerpt
                FROM semantic_embeddings e
                JOIN google_drive_base_information b ON b.id = e.base_information_id
                JOIN google_drive_connections c ON c.id = b.connection_id
                WHERE e.user_id = :userId AND c.user_id = :userId AND e.source_type = 'PROFILE_DOCUMENT'
                  AND e.status = 'READY' AND e.model = :model AND e.dimensions = :dimensions
                """);
        if (baseInformationId != null) sql.append(" AND e.base_information_id = :baseInformationId");
        sql.append(" ORDER BY b.document_name ASC, e.base_information_id ASC");
        return jdbc.query(sql.toString(), new MapSqlParameterSource()
                        .addValue("userId", userId).addValue("model", model).addValue("dimensions", dimensions)
                        .addValue("baseInformationId", baseInformationId).addValue("excerptChars", excerptChars),
                (rs, row) -> new ProfileDocument(rs.getObject("base_information_id", UUID.class),
                        rs.getString("document_name"), rs.getString("excerpt")));
    }

    private static String conflictColumn(String sourceType) {
        return switch (sourceType) {
            case JOB_APPLICATION -> "job_application_id";
            case PROFILE_DOCUMENT -> "base_information_id";
            default -> throw new IllegalArgumentException("Unknown source type: " + sourceType);
        };
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    public record ApplicationSource(UUID applicationId, UUID userId, String vacancyName, String organization,
                                    String note, LocalDateTime updatedAt, UUID embeddingId, String contentHash,
                                    String model, Integer dimensions) {}

    public record ProfileSource(UUID baseInformationId, UUID userId, String docType, String googleFileId,
                                UUID embeddingId, String contentHash, String model, Integer dimensions,
                                int attempts) {}

    public record ClaimedEmbedding(UUID id, String contentHash, String content, int attempts) {}

    public record ProfileDocument(UUID baseInformationId, String documentName, String excerpt) {}

    public record Coverage(long applications, long readyApplications, long emptyApplications, long pending,
                           long exhausted, long sourceErrors) {
        public double applicationRatio() {
            long eligible = applications - emptyApplications;
            return eligible <= 0 ? 1.0 : Math.min(1.0, (double) readyApplications / eligible);
        }
    }
}
