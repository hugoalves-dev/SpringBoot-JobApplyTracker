package com.jobtracker.service.semantic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit, deterministic check of must-have terms (stack, seniority, location, modality, salary wording)
 * against the stored vacancy text. Similarity can rank; only this decides "requirement present".
 * Matching is case- and accent-insensitive with token boundaries, so "Java" does not match "JavaScript"
 * and "remoto" matches "Remoto".
 */
public final class RequirementChecker {
    private static final int EVIDENCE_RADIUS = 60;

    private RequirementChecker() {}

    public static List<RequirementCheck> check(List<String> terms, String... texts) {
        if (terms == null || terms.isEmpty()) return List.of();
        String haystack = String.join("\n", java.util.Arrays.stream(texts).map(t -> t == null ? "" : t).toList());
        String folded = fold(haystack);
        List<RequirementCheck> checks = new ArrayList<>();
        for (String raw : terms) {
            if (raw == null || raw.isBlank()) continue;
            String term = raw.strip();
            Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(fold(term)) + "(?![\\p{L}\\p{N}+#])")
                    .matcher(folded);
            if (m.find()) {
                int start = Math.max(0, m.start() - EVIDENCE_RADIUS);
                int end = Math.min(haystack.length(), m.end() + EVIDENCE_RADIUS);
                String evidence = haystack.substring(start, end).replaceAll("\\s+", " ").strip();
                checks.add(new RequirementCheck(term, true, evidence));
            } else {
                checks.add(new RequirementCheck(term, false, null));
            }
        }
        return checks;
    }

    /** Lower-case and strip accents while keeping string length stable for evidence offsets. */
    private static String fold(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            String c = String.valueOf(value.charAt(i));
            String base = Normalizer.normalize(c, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            sb.append(base.length() == 1 ? base.toLowerCase(Locale.ROOT).charAt(0) : value.charAt(i));
        }
        return sb.toString();
    }

    /** @param evidence surrounding text from the vacancy, or null when the term is absent. */
    public record RequirementCheck(String term, boolean present, String evidence) {}
}
