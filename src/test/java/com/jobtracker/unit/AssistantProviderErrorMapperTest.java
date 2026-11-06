package com.jobtracker.unit;

import com.google.genai.errors.ApiException;
import com.openai.errors.RateLimitException;
import com.jobtracker.service.assistant.AssistantProviderErrorMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AssistantProviderErrorMapperTest {
    private final AssistantProviderErrorMapper mapper = new AssistantProviderErrorMapper();

    @Test
    void mapsGeminiRateLimitWithProviderRetryDelay() {
        RuntimeException error = new RuntimeException(
                "Failed to generate content",
                new ApiException(
                        429,
                        "RESOURCE_EXHAUSTED",
                        "Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests. Please retry in 35.878391973s."
                )
        );

        var payload = mapper.map(error);

        assertThat(payload.code()).isEqualTo("RATE_LIMITED");
        assertThat(payload.message()).isEqualTo("Gemini rate limit exceeded");
        assertThat(payload.retryAfterSeconds()).isEqualTo(36);
    }

    @Test
    void doesNotInventRetryDelayWhenGeminiOmitsIt() {
        RuntimeException error = new RuntimeException(
                "Failed to generate content",
                new ApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for this project")
        );

        var payload = mapper.map(error);

        assertThat(payload.code()).isEqualTo("RATE_LIMITED");
        assertThat(payload.retryAfterSeconds()).isNull();
    }

    @Test
    void mapsOpenRouterRateLimitWithoutInventingRetryDelay() {
        RuntimeException error = new RuntimeException(
                "OpenRouter request failed",
                mock(RateLimitException.class)
        );

        var payload = mapper.map(error);

        assertThat(payload.code()).isEqualTo("RATE_LIMITED");
        assertThat(payload.message()).isEqualTo("OpenRouter rate limit exceeded");
        assertThat(payload.retryAfterSeconds()).isNull();
    }

    @Test
    void mapsNonRateLimitProviderFailuresToGenericUnavailable() {
        RuntimeException error = new RuntimeException(
                "Failed to generate content",
                new ApiException(503, "UNAVAILABLE", "Service unavailable")
        );

        var payload = mapper.map(error);

        assertThat(payload.code()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(payload.message()).isEqualTo("Assistant provider is unavailable");
        assertThat(payload.retryAfterSeconds()).isNull();
    }
}
