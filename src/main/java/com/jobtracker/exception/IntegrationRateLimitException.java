package com.jobtracker.exception;

import java.time.Duration;

/** A per-user integration quota (or the provider's own rate limit) was reached; maps to HTTP 429. */
public class IntegrationRateLimitException extends RuntimeException implements CodedException {

    private final String code;
    private final Duration retryAfter;

    public IntegrationRateLimitException(String code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    @Override
    public String getCode() { return code; }

    public Duration getRetryAfter() { return retryAfter; }
}
