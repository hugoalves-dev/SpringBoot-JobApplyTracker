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
        return jdbc.query(sql.toString(), p, (rs, row) -> new AssistantApplicationView(
                rs.getObject("id", UUID.class), rs.getString("vacancy_name"), rs.getString("organization"),
                rs.getString("recruiter_name"), rs.getString("status"),
                rs.getObject("application_date", LocalDate.class), rs.getString("platform"),
                rs.getInt("interview_count"), rs.getTimestamp("next_step_date_time") == null ? null :
                rs.getTimestamp("next_step_date_time").toLocalDateTime(), rs.getString("note_excerpt"),
                rs.getBoolean("archived"), (Double) rs.getObject("relevance")));
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
        return jdbc.query(sql.toString(), p, (rs, row) -> new AssistantApplicationView(
                rs.getObject("id", UUID.class), rs.getString("vacancy_name"), rs.getString("organization"),
                rs.getString("recruiter_name"), rs.getString("status"),
                rs.getObject("application_date", LocalDate.class), rs.getString("platform"),
                rs.getInt("interview_count"), rs.getTimestamp("next_step_date_time") == null ? null :
                rs.getTimestamp("next_step_date_time").toLocalDateTime(), rs.getString("note_excerpt"),
                rs.getBoolean("archived"), null));
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
}
