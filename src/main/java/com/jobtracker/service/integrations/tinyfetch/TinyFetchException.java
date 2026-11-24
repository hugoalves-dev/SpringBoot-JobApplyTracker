package com.jobtracker.service.integrations.tinyfetch;

import java.time.Duration;

public class TinyFetchException extends RuntimeException {

    public enum Kind { UNAUTHORIZED, QUOTA_EXHAUSTED, RATE_LIMITED, TIMEOUT, UPSTREAM, REJECTED }

    private final Kind kind;
    private final Duration retryAfter;

    public TinyFetchException(Kind kind, String message, Duration retryAfter) {
        super(message);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind getKind() { return kind; }
    public Duration getRetryAfter() { return retryAfter; }
}
