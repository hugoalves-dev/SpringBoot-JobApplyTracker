package com.jobtracker.service.assistant;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.dto.assistant.AssistantApplicationView;
import com.jobtracker.entity.JobApplication;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.assistant.AssistantApplicationQueryRepository;
import com.jobtracker.repository.assistant.AssistantApplicationQueryRepository.ProfileMatchRow;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository.ProfileDocument;
import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.service.semantic.ReciprocalRankFusion;
import com.jobtracker.service.semantic.RequirementChecker;
import com.jobtracker.service.semantic.RequirementChecker.RequirementCheck;
import com.jobtracker.service.semantic.SemanticSearchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AssistantApplicationQueryService {
    private static final Logger log = LoggerFactory.getLogger(AssistantApplicationQueryService.class);
    private static final Set<String> SHORT_TOKENS = Set.of("go", "ai", "c#", "c++", ".net");
    private final AssistantApplicationQueryRepository queries;
    private final ApplicationRepository applications;
    private static final int MAX_REQUIRED_TERMS = 10;
    private static final int PROFILE_EXCERPT_CHARS = 1500;
    private final AssistantProperties properties;
    private final SemanticSearchService semantic;
    private final SemanticSearchProperties semanticProperties;
    private final SemanticEmbeddingRepository embeddings;
    private final MeterRegistry meters;

    public AssistantApplicationQueryService(AssistantApplicationQueryRepository queries,
                                            ApplicationRepository applications,
                                            AssistantProperties properties,
                                            SemanticSearchService semantic,
                                            SemanticSearchProperties semanticProperties,
                                            SemanticEmbeddingRepository embeddings,
                                            MeterRegistry meters) {
        this.queries = queries;
        this.applications = applications;
        this.properties = properties;
        this.semantic = semantic;
        this.semanticProperties = semanticProperties;
        this.embeddings = embeddings;
        this.meters = meters;
    }

    /** No transaction: the query embedding is a network call and must not pin a pooled connection. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AssistantApplicationView> search(UUID userId, String query, String organization,
                                                 String status, String platform, LocalDate from,
                                                 LocalDate to, boolean archived, Integer limit) {
        String normalized = optionalQuery(query);
        if (normalized == null && !hasMeaningfulSearchFilter(organization, status, platform, from, to, archived)) {
            throw new IllegalArgumentException("query or at least one structured filter is required");
        }
        int resultLimit = properties.clampLimit(limit);
        boolean shortToken = normalized != null && usesFallback(normalized);
        if (normalized == null || shortToken || !semantic.isQueryAvailable()) {
            // Filter-only, short-token, or semantic disabled/cooling down: the original FTS path, unchanged.
            return queries.search(userId, normalized, organization, status, platform, from, to,
                    archived, resultLimit, shortToken);
        }
        Timer.Sample sample = Timer.start(meters);
        String mode = "fts_fallback";
        try {
            int candidates = Math.max(resultLimit, semanticProperties.getCandidateLimit());
            List<AssistantApplicationView> keyword = queries.search(userId, normalized, organization, status,
                    platform, from, to, archived, candidates, false);
            Optional<String> vector = semantic.embedQuery(normalized);
            if (vector.isEmpty()) {
                return keyword.stream().limit(resultLimit)
                        .map(v -> v.withMatchedBy(ReciprocalRankFusion.KEYWORD)).toList();
            }
            List<AssistantApplicationView> similar;
            try {
                similar = queries.semanticSearch(userId, vector.get(), semantic.model(), semantic.dimensions(),
                        semanticProperties.getMaxCosineDistance(), organization, status, platform, from, to,
                        archived, candidates);
            } catch (DataAccessException ex) {
                log.warn("event=SEMANTIC_VECTOR_QUERY_FAILED fallback=fts reason={}", ex.getMessage());
                return keyword.stream().limit(resultLimit)
                        .map(v -> v.withMatchedBy(ReciprocalRankFusion.KEYWORD)).toList();
            }
            mode = "hybrid";
            return ReciprocalRankFusion.fuse(keyword, similar, semanticProperties.getRrfK(), resultLimit);
        } finally {
            sample.stop(meters.timer("assistant.search.latency", "mode", mode));
        }
    }

    /**
     * Ranks the user's applications by semantic closeness to their embedded profile document(s) and
     * checks each must-have term explicitly against the vacancy text. Exposes ranks, never distances.
     */
    public ProfileMatchResult matchProfile(UUID userId, UUID baseInformationId, List<String> requiredTerms,
                                           boolean onlyFullyMatching, String organization, String status,
                                           String platform, LocalDate from, LocalDate to, boolean archived,
                                           Integer limit) {
        if (!semantic.isIndexingEnabled()) {
            return ProfileMatchResult.unavailable("SEMANTIC_SEARCH_DISABLED",
                    "Semantic matching is not enabled. Use searchApplications with explicit keywords instead.");
        }
        List<ProfileDocument> profile = embeddings.findReadyProfileDocuments(userId, semantic.model(),
                semantic.dimensions(), baseInformationId, PROFILE_EXCERPT_CHARS);
        if (profile.isEmpty()) {
            return ProfileMatchResult.unavailable("NO_PROFILE_EMBEDDING",
                    "No indexed profile document is available yet. The user can add a base information "
                            + "document in Google Drive settings; indexing runs in the background.");
        }
        List<String> terms = requiredTerms == null ? List.of() : requiredTerms.stream()
                .filter(t -> t != null && !t.isBlank()).map(String::strip).distinct().limit(MAX_REQUIRED_TERMS).toList();
        int resultLimit = properties.clampLimit(limit);
        int fetch = onlyFullyMatching && !terms.isEmpty()
                ? Math.max(resultLimit, semanticProperties.getCandidateLimit()) : resultLimit;
        List<ProfileMatchRow> rows = queries.profileMatches(userId, baseInformationId, semantic.model(),
                semantic.dimensions(), organization, status, platform, from, to, archived, fetch);
        List<ApplicationMatch> matches = new java.util.ArrayList<>();
        int rank = 0;
        for (ProfileMatchRow row : rows) {
            rank++;
            AssistantApplicationView app = row.application();
            List<RequirementCheck> checks = RequirementChecker.check(terms, app.vacancyName(),
                    app.organization(), row.fullNote());
            if (onlyFullyMatching && checks.stream().anyMatch(c -> !c.present())) continue;
            matches.add(new ApplicationMatch(app.withMatchedBy(ReciprocalRankFusion.SEMANTIC), rank, checks));
            if (matches.size() >= resultLimit) break;
        }
        return new ProfileMatchResult("OK", profile, matches, ProfileMatchResult.GUIDANCE);
    }

    public AssistantApplicationView get(UUID userId, UUID id) {
        JobApplication a = applications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        return new AssistantApplicationView(a.getId(), a.getVacancyName(), a.getOrganization(),
                a.getRecruiterName(), a.getStatus(), a.getApplicationDate(), a.getPlatform(),
                a.getInterviewCount(), a.getNextStepDateTime(), excerpt(a.getNote()),
                a.isArchived(), null);
    }

    public AssistantApplicationQueryRepository.ApplicationStats stats(UUID userId, String query,
            String organization, String status, String platform, LocalDate from, LocalDate to,
            boolean archived) {
        String normalized = optionalQuery(query);
        return queries.stats(userId, normalized, organization, status, platform, from, to,
                archived, normalized != null && usesFallback(normalized));
    }

    public List<AssistantApplicationView> timeline(UUID userId, String organization, String status,
            LocalDate from, LocalDate to, boolean archived, boolean oldestFirst, Integer limit) {
        return queries.timeline(userId, organization, status, from, to, archived, oldestFirst,
                properties.clampLimit(limit));
    }

    private String optionalQuery(String query) {
        if (query == null || query.isBlank()) return null;
        return query.trim();
    }

    private boolean hasMeaningfulSearchFilter(String organization, String status, String platform,
                                               LocalDate from, LocalDate to, boolean archived) {
        return hasText(organization) || hasText(status) || hasText(platform)
                || from != null || to != null || archived;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean usesFallback(String query) {
        String normalized = query.toLowerCase();
        return SHORT_TOKENS.contains(normalized) || normalized.length() < 3;
    }

    private String excerpt(String note) {
        if (note == null || note.length() <= 500) return note;
        return note.substring(0, 500) + "…";
    }

    public record ApplicationMatch(AssistantApplicationView application, int semanticRank,
                                   List<RequirementCheck> requirementChecks) {}

    public record ProfileMatchResult(String status, List<ProfileDocument> profileDocuments,
                                     List<ApplicationMatch> matches, String guidance) {
        static final String GUIDANCE = "semanticRank orders applications by textual similarity to the profile "
                + "documents only. It is not a fit or suitability score and must never be shown as a percentage. "
                + "A requirement is satisfied only when requirementChecks marks it present; explain gaps using "
                + "the vacancy text and profile excerpts, and do not invent qualifications.";

        static ProfileMatchResult unavailable(String status, String guidance) {
            return new ProfileMatchResult(status, List.of(), List.of(), guidance);
        }
    }
}
