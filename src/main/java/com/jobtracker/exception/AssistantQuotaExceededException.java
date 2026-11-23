package com.jobtracker.exception;

import java.time.Instant;

/** Raised before any provider call when an Ask ApplyWell limit blocks the request (HTTP 429). */
public class AssistantQuotaExceededException extends RuntimeException implements CodedException {

    public enum Reason {
        DAILY_QUOTA("ASSISTANT_DAILY_QUOTA_EXCEEDED", "Daily assistant message limit reached"),
        RATE_LIMITED("ASSISTANT_RATE_LIMITED", "Too many assistant messages. Please wait a moment"),
        CONCURRENT_REQUEST("ASSISTANT_REQUEST_IN_PROGRESS", "Another assistant response is still being generated"),
        GLOBAL_LIMIT("ASSISTANT_GLOBAL_LIMIT_REACHED", "The assistant is temporarily at capacity");

        private final String code;
        private final String message;

        Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() { return code; }
        public String message() { return message; }
    }

    private final Reason reason;
    private final Long retryAfterSeconds;
    private final Instant retryAt;

    public AssistantQuotaExceededException(Reason reason, Long retryAfterSeconds, Instant retryAt) {
        super(reason.message());
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
        this.retryAt = retryAt;
    }

    @Override
    public String getCode() { return reason.code(); }
    public Reason getReason() { return reason; }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }
    public Instant getRetryAt() { return retryAt; }
}
