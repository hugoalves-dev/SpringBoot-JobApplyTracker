package com.jobtracker.repository.assistant;

import com.jobtracker.dto.assistant.AssistantApplicationView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class AssistantApplicationQueryRepository {
    private static final String COLUMNS = """
            SELECT id, vacancy_name, organization, recruiter_name, status, application_date,
                   platform, interview_count, next_step_date_time,
                   LEFT(note, 500) AS note_excerpt, archived
            """;
    private static final String MATCH = "assistant_search_vector @@ websearch_to_tsquery('simple', :query)";
    private static final String RANK = "ts_rank(assistant_search_vector, websearch_to_tsquery('simple', :query))";

    private final NamedParameterJdbcTemplate jdbc;

    public AssistantApplicationQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssistantApplicationView> search(UUID userId, String query, String organization,
                                                  String status, String platform, LocalDate from,
                                                  LocalDate to, boolean archived, int limit,
                                                  boolean shortTokenFallback) {
        boolean hasQuery = query != null && !query.isBlank();
        StringBuilder sql = new StringBuilder(COLUMNS);
        if (!hasQuery || shortTokenFallback) {
            sql.append(", NULL AS relevance FROM job_applications WHERE user_id = :userId ");
        } else {
            sql.append(", ").append(RANK).append(" AS relevance FROM job_applications WHERE user_id = :userId AND ")
                    .append(MATCH).append(" ");
        }
        MapSqlParameterSource p = baseParameters(userId, organization, status, platform, from, to, archived)
                .addValue("query", query)
                .addValue("likeQuery", hasQuery ? "%" + escapeLike(query) + "%" : null)
                .addValue("limit", limit);
        appendFilters(sql, organization, status, platform, from, to);
        if (hasQuery && shortTokenFallback) {
            sql.append(" AND (vacancy_name ILIKE :likeQuery ESCAPE E'\\\\' OR organization ILIKE :likeQuery ESCAPE E'\\\\' ")
                    .append("OR recruiter_name ILIKE :likeQuery ESCAPE E'\\\\' OR note ILIKE :likeQuery ESCAPE E'\\\\') ");
        }
        sql.append(!hasQuery || shortTokenFallback
                ? " ORDER BY application_date DESC, created_at DESC, id ASC LIMIT :limit"
                : " ORDER BY relevance DESC, application_date DESC, id ASC LIMIT :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> view(rs, relevance(rs)));
    }

    public ApplicationStats stats(UUID userId, String query, String organization, String status,
                                  String platform, LocalDate from, LocalDate to, boolean archived,
                                  boolean shortTokenFallback) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) total, COALESCE(SUM(interview_count), 0) interviews FROM job_applications WHERE user_id = :userId ");
        MapSqlParameterSource p = baseParameters(userId, organization, status, platform, from, to, archived)
                .addValue("query", query)
                .addValue("likeQuery", query == null ? null : "%" + escapeLike(query) + "%");
        appendFilters(sql, organization, status, platform, from, to);
        if (query != null && !query.isBlank()) {
            if (shortTokenFallback) {
                sql.append(" AND (vacancy_name ILIKE :likeQuery ESCAPE E'\\\\' OR organization ILIKE :likeQuery ESCAPE E'\\\\' ")
                        .append("OR recruiter_name ILIKE :likeQuery ESCAPE E'\\\\' OR note ILIKE :likeQuery ESCAPE E'\\\\') ");
            } else {
                sql.append(" AND ").append(MATCH);
            }
        }
        return jdbc.queryForObject(sql.toString(), p,
                (rs, row) -> new ApplicationStats(rs.getLong("total"), rs.getLong("interviews")));
    }

    public List<AssistantApplicationView> timeline(UUID userId, String organization, String status,
                                                    LocalDate from, LocalDate to, boolean archived,
                                                    boolean oldestFirst, int limit) {
        StringBuilder sql = new StringBuilder(COLUMNS).append(", NULL AS relevance FROM job_applications WHERE user_id = :userId ");
        MapSqlParameterSource p = baseParameters(userId, organization, status, null, from, to, archived)
                .addValue("limit", limit);
        appendFilters(sql, organization, status, null, from, to);
        sql.append(oldestFirst ? " ORDER BY application_date ASC, created_at ASC, id ASC" :
                " ORDER BY application_date DESC, created_at DESC, id ASC").append(" LIMIT :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> view(rs, null));
    }

    /**
     * Nearest applications to a query vector, scoped to one user and the active embedding space.
     * The MATERIALIZED CTE forces an exact per-user scan: an ANN index scan would rank globally and
     * filter by tenant afterwards, dropping valid neighbours. Distance never leaves this method.
     */
    public List<AssistantApplicationView> semanticSearch(UUID userId, String queryVector, String model,
                                                         int dimensions, double maxDistance, String organization,
                                                         String status, String platform, LocalDate from,
                                                         LocalDate to, boolean archived, int limit) {
        StringBuilder sql = new StringBuilder("""
                WITH nearest AS MATERIALIZED (
                    SELECT job_application_id, embedding <=> CAST(:queryVector AS vector) AS distance
                    FROM semantic_embeddings
                    WHERE user_id = :userId AND source_type = 'JOB_APPLICATION' AND status = 'READY'
                      AND model = :model AND dimensions = :dimensions AND embedding IS NOT NULL)
                """).append(COLUMNS).append("""
                , NULL AS relevance FROM job_applications
                JOIN nearest ON nearest.job_application_id = job_applications.id
                WHERE user_id = :userId AND nearest.distance <= :maxDistance
                """);
        MapSqlParameterSource p = baseParameters(userId, organization, status, platform, from, to, archived)
                .addValue("queryVector", queryVector).addValue("model", model)
                .addValue("dimensions", dimensions).addValue("maxDistance", maxDistance).addValue("limit", limit);
        appendFilters(sql, organization, status, platform, from, to);
        sql.append(" ORDER BY nearest.distance ASC, job_applications.id ASC LIMIT :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> view(rs, null));
    }

    /**
     * Applications ordered by closeness to the user's embedded profile document(s); with several documents
     * the closest one counts. Returns the text the match was computed from so requirements can be checked
     * against real content instead of inferred from similarity.
     */
    public List<ProfileMatchRow> profileMatches(UUID userId, UUID baseInformationId, String model, int dimensions,
                                                String organization, String status, String platform,
                                                LocalDate from, LocalDate to, boolean archived, int limit) {
        StringBuilder sql = new StringBuilder("""
                WITH profile AS MATERIALIZED (
                    SELECT embedding FROM semantic_embeddings
                    WHERE user_id = :userId AND source_type = 'PROFILE_DOCUMENT' AND status = 'READY'
                      AND model = :model AND dimensions = :dimensions AND embedding IS NOT NULL
                """);
        if (baseInformationId != null) sql.append(" AND base_information_id = :baseInformationId");
        sql.append("""
                ), apps AS MATERIALIZED (
                    SELECT job_application_id, embedding FROM semantic_embeddings
                    WHERE user_id = :userId AND source_type = 'JOB_APPLICATION' AND status = 'READY'
                      AND model = :model AND dimensions = :dimensions AND embedding IS NOT NULL
                ), nearest AS (
                    SELECT apps.job_application_id, MIN(apps.embedding <=> profile.embedding) AS distance
                    FROM apps CROSS JOIN profile GROUP BY apps.job_application_id)
                """).append(COLUMNS).append("""
                , NULL AS relevance, LEFT(note, 20000) AS full_note FROM job_applications
                JOIN nearest ON nearest.job_application_id = job_applications.id
                WHERE user_id = :userId
                """);
        MapSqlParameterSource p = baseParameters(userId, organization, status, platform, from, to, archived)
                .addValue("model", model).addValue("dimensions", dimensions)
                .addValue("baseInformationId", baseInformationId).addValue("limit", limit);
        appendFilters(sql, organization, status, platform, from, to);
        sql.append(" ORDER BY nearest.distance ASC, job_applications.id ASC LIMIT :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> new ProfileMatchRow(view(rs, null),
                rs.getString("full_note")));
    }

    /** ts_rank returns PostgreSQL {@code real}, which the driver maps to Float, not Double. */
    private static Double relevance(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object value = rs.getObject("relevance");
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static AssistantApplicationView view(java.sql.ResultSet rs, Double relevance) throws java.sql.SQLException {
        return new AssistantApplicationView(
                rs.getObject("id", UUID.class), rs.getString("vacancy_name"), rs.getString("organization"),
                rs.getString("recruiter_name"), rs.getString("status"),
                rs.getObject("application_date", LocalDate.class), rs.getString("platform"),
                rs.getInt("interview_count"), rs.getTimestamp("next_step_date_time") == null ? null :
                rs.getTimestamp("next_step_date_time").toLocalDateTime(), rs.getString("note_excerpt"),
                rs.getBoolean("archived"), relevance);
    }

    private MapSqlParameterSource baseParameters(UUID userId, String organization, String status,
                                                  String platform, LocalDate from, LocalDate to, boolean archived) {
        return new MapSqlParameterSource()
                .addValue("userId", userId).addValue("archived", archived)
                .addValue("organization", organization).addValue("status", status)
                .addValue("platform", platform).addValue("from", from).addValue("to", to);
    }

    private void appendFilters(StringBuilder sql, String organization, String status, String platform,
                               LocalDate from, LocalDate to) {
        sql.append(" AND archived = :archived");
        if (organization != null && !organization.isBlank()) sql.append(" AND LOWER(organization) = LOWER(:organization)");
        if (status != null && !status.isBlank()) sql.append(" AND LOWER(status) = LOWER(:status)");
        if (platform != null && !platform.isBlank()) sql.append(" AND LOWER(platform) = LOWER(:platform)");
        if (from != null) sql.append(" AND application_date >= :from");
        if (to != null) sql.append(" AND application_date <= :to");
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record ApplicationStats(long applications, long interviews) {}

    public record ProfileMatchRow(AssistantApplicationView application, String fullNote) {}
}
