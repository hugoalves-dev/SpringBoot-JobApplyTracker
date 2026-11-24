package com.jobtracker.dto.integrations;

public record IntegrationTestResponse(boolean success, String message, long latencyMs) {}
