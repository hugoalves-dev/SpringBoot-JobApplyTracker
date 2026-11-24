package com.jobtracker.unit.integrations;

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
import com.jobtracker.service.integrations.gmail.DraftOutcomeUnknownException;
import com.jobtracker.service.integrations.gmail.EmailDraftLedger;
import com.jobtracker.service.integrations.gmail.GmailApiClient;
import com.jobtracker.service.integrations.gmail.GmailCredentialService;
import com.jobtracker.service.integrations.gmail.GmailDraftService;
import com.jobtracker.service.integrations.gmail.MimeDraftBuilder;
import com.jobtracker.util.SecurityUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GmailDraftServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();

    private final GmailAccountRepository accountRepository = mock(GmailAccountRepository.class);
    private final GmailCredentialService credentialService = mock(GmailCredentialService.class);
    private final GmailApiClient apiClient = mock(GmailApiClient.class);
    private final EmailDraftLedger ledger = mock(EmailDraftLedger.class);
    private final GoogleDriveGeneratedResumeDownloadService resumes = mock(GoogleDriveGeneratedResumeDownloadService.class);
    private final SecurityUtils securityUtils = mock(SecurityUtils.class);
    private final GmailAccount java = account("java@gmail.com", "Java");
    private final GmailAccount csharp = account("csharp@gmail.com", "CSharp");
    private final EmailDraftRequest entry = new EmailDraftRequest();
    private GmailDraftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new GmailDraftService(accountRepository, credentialService, apiClient, new MimeDraftBuilder(), ledger,
                resumes, securityUtils, new IntegrationProperties(), new SimpleMeterRegistry());
        when(securityUtils.getCurrentUserId()).thenReturn(USER);
        when(accountRepository.findByUserIdOrderByCreatedAtAsc(USER)).thenReturn(List.of(java, csharp));
        when(accountRepository.findByUserIdAndProfileNameIgnoreCase(USER, "java")).thenReturn(Optional.of(java));
        when(accountRepository.findByIdAndUserId(java.getId(), USER)).thenReturn(Optional.of(java));
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        when(ledger.claim(eq(USER), anyString(), anyString(), any(), any())).thenReturn(new EmailDraftLedger.Claim(entry, false));
        when(credentialService.call(eq(USER), any(), any())).thenAnswer(inv ->
                ((Function<String, Object>) inv.getArgument(2)).apply("access-token"));
        when(apiClient.createDraft(eq("access-token"), anyString())).thenReturn(new GmailApiClient.CreatedDraft("r-1", "m-1"));
        when(resumes.downloadAsPdf(APPLICATION)).thenReturn(new DownloadedFile("CV - Java.pdf", "application/pdf", "%PDF".getBytes()));
    }

    @Test
    void createsDraftWithResumeInSelectedProfile() {
        EmailDraftResponse response = service.createDraft(request("java", APPLICATION, null, null));

        assertThat(response.status()).isEqualTo(EmailDraftResponse.Status.CREATED);
        assertThat(response.draftId()).isEqualTo("r-1");
        assertThat(response.accountEmail()).isEqualTo("java@gmail.com");
        assertThat(response.attachmentFileName()).isEqualTo("CV - Java.pdf");
        assertThat(response.message()).contains("NOT sent");
        verify(ledger).markSucceeded(entry.getId(), "r-1", "m-1");
        verify(resumes).downloadAsPdf(APPLICATION);
    }

    @Test
    void attachResumeFalseSkipsAttachment() {
        EmailDraftResponse response = service.createDraft(request("java", APPLICATION, false, null));

        assertThat(response.attachmentFileName()).isNull();
        verifyNoInteractions(resumes);
    }

    @Test
    void repeatedRequestReturnsExistingDraftWithoutCallingGmail() {
        entry.setGmailDraftId("r-1");
        when(ledger.claim(eq(USER), anyString(), anyString(), any(), any())).thenReturn(new EmailDraftLedger.Claim(entry, true));

        EmailDraftResponse response = service.createDraft(request("java", null, null, null));

        assertThat(response.status()).isEqualTo(EmailDraftResponse.Status.ALREADY_CREATED);
        assertThat(response.deduplicated()).isTrue();
        verifyNoInteractions(apiClient);
    }

    @Test
    void identicalRequestsDeriveTheSameKeyAndDifferentOnesDoNot() {
        service.createDraft(request("java", null, null, null));
        service.createDraft(request("java", null, null, null));
        CreateEmailDraftRequest different = new CreateEmailDraftRequest(List.of("hr@corp.com"), null, null,
                "Other subject", "<p>Hi</p>", null, "java", null, null, null);
        service.createDraft(different);

        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ledger, org.mockito.Mockito.times(3)).claim(eq(USER), keys.capture(), anyString(), any(), any());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1)).startsWith("auto:");
        assertThat(keys.getAllValues().get(2)).isNotEqualTo(keys.getAllValues().get(0));
    }

    @Test
    void ambiguousOutcomeIsRecordedAndNotRetried() {
        when(apiClient.createDraft(anyString(), anyString())).thenThrow(new DraftOutcomeUnknownException("timeout"));

        assertThatThrownBy(() -> service.createDraft(request("java", null, null, null)))
                .hasFieldOrPropertyWithValue("code", "EMAIL_DRAFT_OUTCOME_UNKNOWN");
        verify(ledger).markUnknown(eq(entry.getId()), anyString());
        verify(ledger, never()).markSucceeded(any(), any(), any());
    }

    @Test
    void attachmentFailureMarksRequestFailedSoItCanBeRetried() {
        when(resumes.downloadAsPdf(APPLICATION)).thenThrow(new BadRequestException("Generate a resume first before downloading it"));

        assertThatThrownBy(() -> service.createDraft(request("java", APPLICATION, null, null)))
                .isInstanceOf(BadRequestException.class);
        verify(ledger).markFailed(eq(entry.getId()), anyString());
        verifyNoInteractions(apiClient);
    }

    @Test
    void requiresExplicitAccountWhenSeveralAreConnected() {
        assertThatThrownBy(() -> service.createDraft(request(null, null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Java <java@gmail.com>");
    }

    @Test
    void cannotUseAnotherUsersAccount() {
        UUID foreign = UUID.randomUUID();
        when(accountRepository.findByIdAndUserId(foreign, USER)).thenReturn(Optional.empty());
        CreateEmailDraftRequest request = new CreateEmailDraftRequest(List.of("hr@corp.com"), null, null, "S", "<p>x</p>",
                foreign, null, null, null, null);

        assertThatThrownBy(() -> service.createDraft(request)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(ledger, apiClient);
    }

    @Test
    void rejectsHeaderInjectionAndInvalidAddresses() {
        CreateEmailDraftRequest injected = new CreateEmailDraftRequest(List.of("hr@corp.com"), null, null,
                "Hello\r\nBcc: attacker@evil.com", "<p>x</p>", null, "java", null, null, null);
        CreateEmailDraftRequest invalid = new CreateEmailDraftRequest(List.of("not-an-email"), null, null,
                "Hello", "<p>x</p>", null, "java", null, null, null);
        CreateEmailDraftRequest injectedAddress = new CreateEmailDraftRequest(List.of("hr@corp.com\nBcc: x@evil.com"), null, null,
                "Hello", "<p>x</p>", null, "java", null, null, null);

        assertThatThrownBy(() -> service.createDraft(injected)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createDraft(invalid)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createDraft(injectedAddress)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledger, apiClient);
    }

    @Test
    void attachResumeWithoutApplicationIsRejected() {
        assertThatThrownBy(() -> service.createDraft(request("java", null, true, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void explicitIdempotencyKeyIsUsedAsIs() {
        service.createDraft(request("java", null, null, "client-key-1"));

        verify(ledger).claim(eq(USER), eq("client-key-1"), anyString(), eq(java.getId()), isNull());
    }

    private static CreateEmailDraftRequest request(String profile, UUID applicationId, Boolean attach, String key) {
        return new CreateEmailDraftRequest(List.of("Recruiter <hr@corp.com>"), List.of(), null,
                "Candidatura Java", "<p>Olá!</p>", null, profile, applicationId, attach, key);
    }

    private static GmailAccount account(String email, String profile) {
        GmailAccount account = new GmailAccount();
        account.setId(UUID.randomUUID());
        account.setEmail(email);
        account.setProfileName(profile);
        return account;
    }
}
