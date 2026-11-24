package com.jobtracker.dto.integrations;

import java.time.LocalDateTime;

public record TinyFetchSettingsResponse(
        boolean configured,
        boolean enabled,
        String apiKeyHint,
        int perMinuteLimit,
        int dailyLimit,
        int maxPerMinuteLimit,
        int maxDailyLimit,
        int usedToday,
        LocalDateTime lastTestedAt,
        Boolean lastTestSuccess,
        String lastTestMessage,
        boolean serverReady
) {}
