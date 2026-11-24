package com.jobtracker.service.integrations.gmail;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.dto.integrations.CreateEmailDraftRequest;
import com.jobtracker.dto.integrations.EmailDraftResponse;
import com.jobtracker.entity.integrations.EmailDraftRequest;
import com.jobtracker.entity.integrations.GmailAccount;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.integrations.GmailAccountRepository;
import com.jobtracker.service.GoogleDriveGeneratedResumeDownloadService;
import com.jobtracker.service.GoogleDriveGeneratedResumeDownloadService.DownloadedFile;
import com.jobtracker.util.SecurityUtils;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates Gmail drafts natively (users.drafts.create). Drafts only: nothing here sends email or
 * changes the application's status — the user reviews and sends from Gmail. A failure here never
 * touches the application or its generated resume.
 */
@Service
public class GmailDraftService {

    private static final Logger log = LoggerFactory.getLogger(GmailDraftService.class);
    private static final int MAX_RECIPIENTS = 50;

    private final GmailAccountRepository accountRepository;
    private final GmailCredentialService credentialService;
    private final GmailApiClient apiClient;
    private final MimeDraftBuilder mimeBuilder;
    private final EmailDraftLedger ledger;
    private final GoogleDriveGeneratedResumeDownloadService resumeDownloadService;
    private final SecurityUtils securityUtils;
    private final IntegrationProperties.Gmail properties;
    private final MeterRegistry meters;

    public GmailDraftService(GmailAccountRepository accountRepository,
                             GmailCredentialService credentialService,
                             GmailApiClient apiClient,
                             MimeDraftBuilder mimeBuilder,
                             EmailDraftLedger ledger,
                             GoogleDriveGeneratedResumeDownloadService resumeDownloadService,
                             SecurityUtils securityUtils,
                             IntegrationProperties properties,
                             MeterRegistry meters) {
        this.accountRepository = accountRepository;
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.mimeBuilder = mimeBuilder;
        this.ledger = ledger;
        this.resumeDownloadService = resumeDownloadService;
        this.securityUtils = securityUtils;
        this.properties = properties.getGmail();
        this.meters = meters;
    }

    public EmailDraftResponse createDraft(CreateEmailDraftRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        List<InternetAddress> to = parseAddresses(request.to(), "to");
        List<InternetAddress> cc = parseAddresses(request.cc(), "cc");
        List<InternetAddress> bcc = parseAddresses(request.bcc(), "bcc");
        if (to.isEmpty()) {
            throw new BadRequestException("At least one recipient is required in to");
        }
        if (to.size() + cc.size() + bcc.size() > MAX_RECIPIENTS) {
            throw new BadRequestException("At most " + MAX_RECIPIENTS + " recipients are allowed");
        }
        String subject = requireSingleLine(request.subject(), "subject");
        String html = request.htmlBody();
        if (html == null || html.isBlank()) {
            throw new BadRequestException("htmlBody is required");
        }
        if (html.length() > properties.getMaxHtmlChars()) {
            throw new BadRequestException("htmlBody exceeds " + properties.getMaxHtmlChars() + " characters");
        }
        boolean attachResume = request.applicationId() != null && !Boolean.FALSE.equals(request.attachResume());
        if (Boolean.TRUE.equals(request.attachResume()) && request.applicationId() == null) {
            throw new BadRequestException("applicationId is required to attach the generated resume");
        }

        GmailAccount account = resolveAccount(userId, request.accountId(), request.profileName());
        String requestHash = hash(account.getId(), to, cc, bcc, subject, html, request.applicationId(), attachResume);
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? "auto:" + requestHash : request.idempotencyKey().trim();

        EmailDraftLedger.Claim claim = ledger.claim(userId, key, requestHash, account.getId(), request.applicationId());
        EmailDraftRequest entry = claim.entry();
        if (claim.alreadySucceeded()) {
            meters.counter("integrations.gmail.drafts", "outcome", "deduplicated").increment();
            return response(EmailDraftResponse.Status.ALREADY_CREATED, entry.getGmailDraftId(), entry.getGmailMessageId(),
                    account, request.applicationId(), null, key, true,
                    "This draft was already created earlier; no duplicate was made.");
        }

        long started = System.nanoTime();
        String outcome = "error";
        try {
            MimeDraftBuilder.Attachment attachment = attachResume ? loadResume(request.applicationId()) : null;
            String raw = mimeBuilder.buildRawBase64Url(account.getEmail(), to, cc, bcc, subject, html, attachment);
            GmailApiClient.CreatedDraft draft = credentialService.call(userId, account.getId(),
                    token -> apiClient.createDraft(token, raw));
            ledger.markSucceeded(entry.getId(), draft.draftId(), draft.messageId());
            outcome = "created";
            return response(EmailDraftResponse.Status.CREATED, draft.draftId(), draft.messageId(), account,
                    request.applicationId(), attachment == null ? null : attachment.fileName(), key, false,
                    "Draft created. It was NOT sent: the user must review and send it from Gmail.");
        } catch (DraftOutcomeUnknownException ex) {
            outcome = "unknown";
            ledger.markUnknown(entry.getId(), ex.getMessage());
            throw EmailDraftLedger.ambiguous();
        } catch (RuntimeException ex) {
            ledger.markFailed(entry.getId(), ex.getMessage());
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            meters.counter("integrations.gmail.drafts", "outcome", outcome).increment();
            meters.timer("integrations.gmail.drafts.latency", "outcome", outcome).record(Duration.ofMillis(latencyMs));
            log.info("event=GMAIL_DRAFT userId={} accountId={} withAttachment={} outcome={} latencyMs={}",
                    userId, account.getId(), attachResume, outcome, latencyMs);
        }
    }

    private GmailAccount resolveAccount(UUID userId, UUID accountId, String profileName) {
        if (accountId != null) {
            return accountRepository.findByIdAndUserId(accountId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Gmail account not found: " + accountId));
        }
        if (profileName != null && !profileName.isBlank()) {
            return accountRepository.findByUserIdAndProfileNameIgnoreCase(userId, profileName.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("No connected Gmail account with profile \""
                            + profileName.trim() + "\". Available: " + profileNames(userId)));
        }
        List<GmailAccount> accounts = accountRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (accounts.isEmpty()) {
            throw new BadRequestException("No Gmail account is connected. Connect one in Settings > Integrations.");
        }
        if (accounts.size() > 1) {
            throw new BadRequestException("Several Gmail accounts are connected; choose one with accountId or profileName. "
                    + "Available: " + profileNames(userId));
        }
        return accounts.getFirst();
    }

    private String profileNames(UUID userId) {
        List<String> names = accountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(account -> account.getProfileName() + " <" + account.getEmail() + ">")
                .toList();
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    /** Reads the PDF internally through the user's Drive connection; ownership is enforced there. */
    private MimeDraftBuilder.Attachment loadResume(UUID applicationId) {
        DownloadedFile file = resumeDownloadService.downloadAsPdf(applicationId);
        if (file.content().length > properties.getMaxAttachmentBytes()) {
            throw new BadRequestException("The resume PDF is larger than the " + properties.getMaxAttachmentBytes()
                    + " bytes allowed for draft attachments");
        }
        return new MimeDraftBuilder.Attachment(file.fileName(), file.contentType(), file.content());
    }

    private EmailDraftResponse response(EmailDraftResponse.Status status, String draftId, String messageId,
                                        GmailAccount account, UUID applicationId, String fileName, String key,
                                        boolean deduplicated, String message) {
        String draftsUrl = UriComponentsBuilder.fromUriString("https://mail.google.com/mail/u/")
                .queryParam("authuser", account.getEmail())
                .fragment("drafts")
                .build()
                .encode()
                .toUriString();
        return new EmailDraftResponse(status, draftId, messageId, account.getId(), account.getEmail(),
                account.getProfileName(), applicationId, fileName, key, deduplicated, draftsUrl, message);
    }

    static List<InternetAddress> parseAddresses(List<String> values, String field) {
        List<InternetAddress> parsed = new ArrayList<>();
        if (values == null) {
            return parsed;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = requireSingleLine(value, field);
            try {
                InternetAddress address = new InternetAddress(trimmed, true);
                address.validate();
                parsed.add(address);
            } catch (AddressException ex) {
                throw new BadRequestException("Invalid email address in " + field + ": " + trimmed);
            }
        }
        return parsed;
    }

    /** Rejects CR/LF so header values can never inject extra headers. */
    static String requireSingleLine(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new BadRequestException(field + " must not contain line breaks");
        }
        return value.trim();
    }

    private static String hash(UUID accountId, List<InternetAddress> to, List<InternetAddress> cc,
                               List<InternetAddress> bcc, String subject, String html, UUID applicationId,
                               boolean attachResume) {
        String canonical = String.join("\u0000",
                accountId.toString(),
                normalized(to), normalized(cc), normalized(bcc),
                subject, html,
                String.valueOf(applicationId), String.valueOf(attachResume));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String normalized(List<InternetAddress> addresses) {
        return addresses.stream().map(a -> a.getAddress().toLowerCase(Locale.ROOT)).sorted().toList().toString();
    }
}
