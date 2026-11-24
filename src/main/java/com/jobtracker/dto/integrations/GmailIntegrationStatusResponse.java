package com.jobtracker.dto.integrations;

import java.util.List;

public record GmailIntegrationStatusResponse(
        boolean serverConfigured,
        int maxAccounts,
        List<String> scopes,
        List<GmailAccountResponse> accounts
) {}
