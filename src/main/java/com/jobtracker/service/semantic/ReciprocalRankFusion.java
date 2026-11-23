package com.jobtracker.service.semantic;

import com.jobtracker.dto.assistant.AssistantApplicationView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009): score = Σ 1 / (k + rank). Uses ranks only, so the
 * incomparable scales of ts_rank and cosine distance never mix, and no score is exposed as a percentage.
 */
public final class ReciprocalRankFusion {
    public static final String KEYWORD = "KEYWORD";
    public static final String SEMANTIC = "SEMANTIC";
    public static final String BOTH = "KEYWORD_AND_SEMANTIC";

    private ReciprocalRankFusion() {}

    public static List<AssistantApplicationView> fuse(List<AssistantApplicationView> keyword,
                                                      List<AssistantApplicationView> semantic, int k, int limit) {
        Map<UUID, Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < keyword.size(); i++) {
            AssistantApplicationView view = keyword.get(i);
            entries.computeIfAbsent(view.id(), id -> new Entry(view)).add(KEYWORD, i + 1, k);
        }
        for (int i = 0; i < semantic.size(); i++) {
            AssistantApplicationView view = semantic.get(i);
            entries.computeIfAbsent(view.id(), id -> new Entry(view)).add(SEMANTIC, i + 1, k);
        }
        List<Entry> ranked = new ArrayList<>(entries.values());
        // Ties: an exact keyword hit wins, then the better keyword rank, then id for determinism.
        ranked.sort(Comparator.comparingDouble(Entry::score).reversed()
                .thenComparing(e -> e.keywordRank == 0)
                .thenComparingInt(e -> e.keywordRank == 0 ? Integer.MAX_VALUE : e.keywordRank)
                .thenComparing(e -> e.view.id()));
        return ranked.stream().limit(limit).map(Entry::result).toList();
    }

    private static final class Entry {
        private final AssistantApplicationView view;
        private double score;
        private int keywordRank;
        private boolean semanticHit;

        private Entry(AssistantApplicationView view) {
            this.view = view;
        }

        private void add(String source, int rank, int k) {
            score += 1.0 / (k + rank);
            if (KEYWORD.equals(source)) keywordRank = rank;
            else semanticHit = true;
        }

        private double score() { return score; }

        private AssistantApplicationView result() {
            String matchedBy = keywordRank > 0 && semanticHit ? BOTH : keywordRank > 0 ? KEYWORD : SEMANTIC;
            return view.withMatchedBy(matchedBy);
        }
    }
}
