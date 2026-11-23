package com.jobtracker.service.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical text that gets embedded. Recruiter names and other personal fields are deliberately excluded:
 * they carry no semantic signal about the role and would only leak PII to the embedding provider.
 */
public final class EmbeddingContent {
    private EmbeddingContent() {}

    public static String forApplication(String vacancyName, String organization, String note, int maxChars) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "Vacancy", vacancyName);
        appendLine(sb, "Organization", organization);
        appendLine(sb, "Notes", note);
        return bound(sb.toString().strip(), maxChars);
    }

    public static String forProfileDocument(String text, int maxChars) {
        return bound(text == null ? "" : text.strip(), maxChars);
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(label).append(": ").append(value.strip()).append('\n');
    }

    private static String bound(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
