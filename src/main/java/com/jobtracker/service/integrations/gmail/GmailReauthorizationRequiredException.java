package com.jobtracker.service.integrations.gmail;

import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.CodedException;

/** The refresh token is no longer usable; the user must reconnect the Gmail account. */
public class GmailReauthorizationRequiredException extends BadRequestException implements CodedException {
    public GmailReauthorizationRequiredException(String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return "GMAIL_REAUTHORIZATION_REQUIRED";
    }
}
