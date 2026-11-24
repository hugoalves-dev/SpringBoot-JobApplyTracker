package com.jobtracker.service.integrations.gmail;

/** Gmail may or may not have created the draft (timeout, connection reset, 5xx). Never auto-retry. */
public class DraftOutcomeUnknownException extends RuntimeException {
    public DraftOutcomeUnknownException(String message) {
        super(message);
    }
}
