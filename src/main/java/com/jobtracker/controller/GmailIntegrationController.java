package com.jobtracker.controller;

import com.jobtracker.dto.integrations.CreateEmailDraftRequest;
import com.jobtracker.dto.integrations.EmailDraftResponse;
import com.jobtracker.dto.integrations.GmailAccountResponse;
import com.jobtracker.dto.integrations.GmailAccountUpdateRequest;
import com.jobtracker.dto.integrations.GmailIntegrationStatusResponse;
import com.jobtracker.dto.integrations.GmailOAuthStartRequest;
import com.jobtracker.dto.integrations.GmailOAuthStartResponse;
import com.jobtracker.dto.integrations.IntegrationTestResponse;
import com.jobtracker.service.integrations.gmail.GmailAccountService;
import com.jobtracker.service.integrations.gmail.GmailDraftService;
import com.jobtracker.config.UserSessionOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

@Tag(name = "Integrations - Gmail", description = "Connect Gmail accounts and create drafts (never sends email)")
@RestController
@RequestMapping("/api/v1/integrations/gmail")
public class GmailIntegrationController {

    private final GmailAccountService accountService;
    private final GmailDraftService draftService;

    public GmailIntegrationController(GmailAccountService accountService, GmailDraftService draftService) {
        this.accountService = accountService;
        this.draftService = draftService;
    }

    @Operation(summary = "Gmail integration status and connected accounts")
    @GetMapping
    public ResponseEntity<GmailIntegrationStatusResponse> status() {
        return ResponseEntity.ok(accountService.getStatus());
    }

    @Operation(summary = "Start the Google OAuth flow to connect (or reconnect) a Gmail account")
    @UserSessionOnly
    @PostMapping("/oauth/start")
    public ResponseEntity<GmailOAuthStartResponse> startOAuth(@Valid @RequestBody(required = false) GmailOAuthStartRequest request) {
        return ResponseEntity.ok(accountService.startAuthorization(request == null ? null : request.profileName()));
    }

    @Operation(summary = "Google OAuth callback for Gmail")
    @GetMapping("/oauth/callback")
    public void oauthCallback(@RequestParam(required = false) String state,
                              @RequestParam(required = false) String code,
                              @RequestParam(required = false) String error,
                              HttpServletResponse response) throws IOException {
        response.sendRedirect(accountService.handleCallback(state, code, error));
    }

    @Operation(summary = "Rename a connected account's profile/alias")
    @UserSessionOnly
    @PatchMapping("/accounts/{accountId}")
    public ResponseEntity<GmailAccountResponse> rename(@PathVariable UUID accountId,
                                                       @Valid @RequestBody GmailAccountUpdateRequest request) {
        return ResponseEntity.ok(accountService.rename(accountId, request.profileName()));
    }

    @Operation(summary = "Verify that the stored authorization still works")
    @PostMapping("/accounts/{accountId}/test")
    public ResponseEntity<IntegrationTestResponse> test(@PathVariable UUID accountId) {
        return ResponseEntity.ok(accountService.test(accountId));
    }

    @Operation(summary = "Disconnect an account and revoke its Google authorization")
    @UserSessionOnly
    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Void> disconnect(@PathVariable UUID accountId) {
        accountService.disconnect(accountId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create a Gmail draft, optionally attaching an application's generated resume PDF")
    @PostMapping("/drafts")
    public ResponseEntity<EmailDraftResponse> createDraft(@Valid @RequestBody CreateEmailDraftRequest request) {
        EmailDraftResponse response = draftService.createDraft(request);
        HttpStatus status = response.deduplicated() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
