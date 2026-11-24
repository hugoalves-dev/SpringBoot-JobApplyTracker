package com.jobtracker.dto.integrations;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FetchUrlRequest(
        @Schema(description = "Public http(s) URL to read, e.g. a job post", example = "https://www.linkedin.com/jobs/view/123")
        @NotBlank(message = "url is required") @Size(max = 2048) String url,
        @Schema(description = "Skip the short-lived cache and fetch again") Boolean bypassCache
) {}
