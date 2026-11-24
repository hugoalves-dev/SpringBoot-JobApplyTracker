package com.jobtracker.dto.integrations;

import java.util.UUID;

public record EmailDraftResponse(
        Status status,
        String draftId,
        String messageId,
        UUID accountId,
        String accountEmail,
        String profileName,
        UUID applicationId,
        String attachmentFileName,
        String idempotencyKey,
        boolean deduplicated,
        String gmailDraftsUrl,
        String message
) {
    public enum Status { CREATED, ALREADY_CREATED }
}
