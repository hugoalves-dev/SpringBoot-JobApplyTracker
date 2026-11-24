package com.jobtracker.dto.integrations;

import java.time.LocalDateTime;
import java.util.UUID;

public record GmailAccountResponse(
        UUID id,
        String email,
        String profileName,
        LocalDateTime connectedAt,
        LocalDateTime lastTestedAt,
        boolean reauthorizationRequired
) {}
