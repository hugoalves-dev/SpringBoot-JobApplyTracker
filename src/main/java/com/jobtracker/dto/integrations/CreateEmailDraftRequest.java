package com.jobtracker.dto.integrations;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(description = "Create a Gmail draft (never sent) in one of the user's connected accounts")
public record CreateEmailDraftRequest(
        @Schema(description = "Recipient addresses") @NotEmpty(message = "to is required") List<String> to,
        List<String> cc,
        List<String> bcc,
        @NotBlank(message = "subject is required") @Size(max = 998) String subject,
        @Schema(description = "HTML body of the email") @NotBlank(message = "htmlBody is required") String htmlBody,
        @Schema(description = "Connected Gmail account id. Takes precedence over profileName.") UUID accountId,
        @Schema(description = "Profile/alias of the connected account, e.g. \"Java\"") String profileName,
        @Schema(description = "Application whose generated resume PDF should be attached") UUID applicationId,
        @Schema(description = "Attach the application's generated resume PDF. Defaults to true when applicationId is set.")
        Boolean attachResume,
        @Schema(description = "Optional client key; repeating a request with the same key never creates a second draft")
        @Size(max = 128) String idempotencyKey
) {}
