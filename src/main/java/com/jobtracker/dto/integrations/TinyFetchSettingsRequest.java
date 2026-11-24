package com.jobtracker.dto.integrations;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Create or update the current user's TinyFish Fetch settings. Omitted fields keep their value.")
public record TinyFetchSettingsRequest(
        @Schema(description = "TinyFish API key. Required on first setup; omit to keep the stored key. Never returned.",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @Size(max = 512, message = "apiKey is too long")
        String apiKey,
        @Schema(description = "Whether URL fetching is enabled") Boolean enabled,
        @Schema(description = "Max fetches per minute; cannot exceed the server/provider limit")
        @Min(1) @Max(10_000) Integer perMinuteLimit,
        @Schema(description = "Max fetches per UTC day; cannot exceed the server/provider limit")
        @Min(1) @Max(1_000_000) Integer dailyLimit
) {}
