package com.jobtracker.dto.assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @param relevance full-text rank of a keyword match, when there is one. Ordering signal only.
 * @param matchedBy which retrieval produced the hit: KEYWORD, SEMANTIC or KEYWORD_AND_SEMANTIC;
 *                  null for non-search reads.
 */
public record AssistantApplicationView(
        UUID id,
        String vacancyName,
        String organization,
        String recruiterName,
        String status,
        LocalDate applicationDate,
        String platform,
        int interviewCount,
        LocalDateTime nextStepDateTime,
        String noteExcerpt,
        boolean archived,
        Double relevance,
        String matchedBy
) {
    public AssistantApplicationView(UUID id, String vacancyName, String organization, String recruiterName,
                                    String status, LocalDate applicationDate, String platform, int interviewCount,
                                    LocalDateTime nextStepDateTime, String noteExcerpt, boolean archived,
                                    Double relevance) {
        this(id, vacancyName, organization, recruiterName, status, applicationDate, platform, interviewCount,
                nextStepDateTime, noteExcerpt, archived, relevance, null);
    }

    public AssistantApplicationView withMatchedBy(String value) {
        return new AssistantApplicationView(id, vacancyName, organization, recruiterName, status, applicationDate,
                platform, interviewCount, nextStepDateTime, noteExcerpt, archived, relevance, value);
    }
}
