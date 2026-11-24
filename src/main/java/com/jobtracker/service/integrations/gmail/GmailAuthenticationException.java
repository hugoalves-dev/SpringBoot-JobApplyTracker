package com.jobtracker.service.integrations.gmail;

/** Gmail returned 401 for an access token; the caller may refresh once and retry. */
public class GmailAuthenticationException extends RuntimeException {
    public GmailAuthenticationException(String message) {
        super(message);
    }
}
