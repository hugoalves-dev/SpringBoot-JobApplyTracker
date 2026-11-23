package com.jobtracker.dto.assistant;

import java.time.Instant;

/** dailyLimit and remaining are null when the daily quota is disabled. */
public record AssistantUsageResponse(
        boolean quotaEnabled,
        Integer dailyLimit,
        long used,
        Integer remaining,
        Instant resetAt,
        String resetTimezone
) {}
