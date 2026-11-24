package com.jobtracker.dto.integrations;

import java.time.LocalDateTime;
import java.util.List;

public record GmailOAuthStartResponse(String authorizationUrl, List<String> scopes, LocalDateTime expiresAt) {}
