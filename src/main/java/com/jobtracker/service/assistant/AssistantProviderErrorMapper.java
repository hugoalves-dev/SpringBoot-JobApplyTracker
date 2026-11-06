package com.jobtracker.service.assistant;

import com.google.genai.errors.ApiException;
import com.openai.errors.RateLimitException;
import com.jobtracker.dto.assistant.AssistantErrorPayload;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AssistantProviderErrorMapper {
    private static final Pattern RETRY_DELAY = Pattern.compile(
            "(?i)\\bretry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)\\s*s\\b"
    );

    public AssistantErrorPayload map(Throwable error) {
        ApiException apiException = findApiException(error);
        if (apiException != null && isRateLimited(apiException)) {
            return new AssistantErrorPayload(
                    "RATE_LIMITED",
                    "Gemini rate limit exceeded",
                    extractRetryAfterSeconds(apiException)
            );
        }

        if (hasOpenRouterRateLimit(error)) {
            return new AssistantErrorPayload(
                    "RATE_LIMITED",
                    "OpenRouter rate limit exceeded",
                    null
            );
        }

        return new AssistantErrorPayload(
                "PROVIDER_UNAVAILABLE",
                "Assistant provider is unavailable",
                null
        );
    }

    private boolean hasOpenRouterRateLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RateLimitException) return true;
            current = current.getCause();
        }
        return false;
    }

    private boolean isRateLimited(ApiException error) {
        if (error.code() == 429) return true;
        if ("RESOURCE_EXHAUSTED".equalsIgnoreCase(error.status())) return true;

        String message = error.message();
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("quota exceeded") || normalized.contains("rate limit");
    }

    private Integer extractRetryAfterSeconds(ApiException error) {
        String message = error.message();
        if (message == null || message.isBlank()) return null;

        Matcher matcher = RETRY_DELAY.matcher(message);
        if (!matcher.find()) return null;

        double seconds = Double.parseDouble(matcher.group(1));
        if (!Double.isFinite(seconds) || seconds < 0) return null;
        return (int) Math.ceil(seconds);
    }

    private ApiException findApiException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException apiException) return apiException;
            current = current.getCause();
        }
        return null;
    }
}
