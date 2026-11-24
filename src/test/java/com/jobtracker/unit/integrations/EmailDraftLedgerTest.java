package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.entity.integrations.EmailDraftRequest;
import com.jobtracker.entity.integrations.EmailDraftRequest.Status;
import com.jobtracker.repository.integrations.EmailDraftRequestRepository;
import com.jobtracker.service.integrations.gmail.EmailDraftLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailDraftLedgerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final EmailDraftRequestRepository repository = mock(EmailDraftRequestRepository.class);
    private EmailDraftLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new EmailDraftLedger(repository, new IntegrationProperties());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstClaimInsertsPendingEntry() {
        when(repository.findByUserIdAndIdempotencyKey(USER, "k")).thenReturn(Optional.empty());

        EmailDraftLedger.Claim claim = ledger.claim(USER, "k", "hash", ACCOUNT, null);

        assertThat(claim.alreadySucceeded()).isFalse();
        assertThat(claim.entry().getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void succeededEntryIsReturnedWithoutNewAttempt() {
        when(repository.findByUserIdAndIdempotencyKey(USER, "k")).thenReturn(Optional.of(entry(Status.SUCCEEDED, "hash", LocalDateTime.now())));

        assertThat(ledger.claim(USER, "k", "hash", ACCOUNT, null).alreadySucceeded()).isTrue();
    }

    @Test
    void reusedKeyWithDifferentContentIsRejected() {
        when(repository.findByUserIdAndIdempotencyKey(USER, "k")).thenReturn(Optional.of(entry(Status.SUCCEEDED, "other", LocalDateTime.now())));

        assertThatThrownBy(() -> ledger.claim(USER, "k", "hash", ACCOUNT, null))
                .hasFieldOrPropertyWithValue("code", "EMAIL_DRAFT_KEY_REUSED");
    }

    @Test
    void recentPendingIsInProgressAndStalePendingBecomesAmbiguous() {
        EmailDraftRequest recent = entry(Status.PENDING, "hash", LocalDateTime.now());
        when(repository.findByUserIdAndIdempotencyKey(USER, "recent")).thenReturn(Optional.of(recent));
        assertThatThrownBy(() -> ledger.claim(USER, "recent", "hash", ACCOUNT, null))
                .hasFieldOrPropertyWithValue("code", "EMAIL_DRAFT_IN_PROGRESS");

        EmailDraftRequest stale = entry(Status.PENDING, "hash", LocalDateTime.now().minusMinutes(10));
        when(repository.findByUserIdAndIdempotencyKey(USER, "stale")).thenReturn(Optional.of(stale));
        assertThatThrownBy(() -> ledger.claim(USER, "stale", "hash", ACCOUNT, null))
                .hasFieldOrPropertyWithValue("code", "EMAIL_DRAFT_OUTCOME_UNKNOWN");
        assertThat(stale.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void unknownOutcomeIsNeverRetriedAutomatically() {
        when(repository.findByUserIdAndIdempotencyKey(USER, "k")).thenReturn(Optional.of(entry(Status.UNKNOWN, "hash", LocalDateTime.now())));

        assertThatThrownBy(() -> ledger.claim(USER, "k", "hash", ACCOUNT, null))
                .hasFieldOrPropertyWithValue("code", "EMAIL_DRAFT_OUTCOME_UNKNOWN");
    }

    @Test
    void definitiveFailureCanBeRetried() {
        when(repository.findByUserIdAndIdempotencyKey(USER, "k")).thenReturn(Optional.of(entry(Status.FAILED, "hash", LocalDateTime.now())));

        EmailDraftLedger.Claim claim = ledger.claim(USER, "k", "hash", ACCOUNT, null);

        assertThat(claim.alreadySucceeded()).isFalse();
        assertThat(claim.entry().getStatus()).isEqualTo(Status.PENDING);
    }

    private static EmailDraftRequest entry(Status status, String hash, LocalDateTime updatedAt) {
        EmailDraftRequest entry = new EmailDraftRequest();
        entry.setUserId(USER);
        entry.setIdempotencyKey("k");
        entry.setRequestHash(hash);
        entry.setGmailAccountId(ACCOUNT);
        entry.setStatus(status);
        ReflectionTestUtils.setField(entry, "updatedAt", updatedAt);
        return entry;
    }
}
