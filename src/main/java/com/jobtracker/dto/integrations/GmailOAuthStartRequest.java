package com.jobtracker.dto.integrations;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record GmailOAuthStartRequest(
        @Schema(description = "Profile/alias for the account, e.g. \"Java\" or \"CSharp\". Defaults to the mailbox name.")
        @Size(max = 60) String profileName
) {}
