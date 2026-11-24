package com.jobtracker.service.integrations.gmail;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.entity.integrations.EmailDraftRequest;
import com.jobtracker.entity.integrations.EmailDraftRequest.Status;
import com.jobtracker.exception.CodedException;
import com.jobtracker.exception.ConflictException;
import com.jobtracker.repository.integrations.EmailDraftRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Idempotency ledger for draft creation. Every state change commits immediately
 * (REQUIRES_NEW) so it is visible to a concurrent or retried request before Gmail is called.
 */
@Component
public class EmailDraftLedger {

    private static final int ERROR_MAX = 500;

    private final EmailDraftRequestRepository repository;
    private final IntegrationProperties.Gmail properties;

    public EmailDraftLedger(EmailDraftRequestRepository repository, IntegrationProperties properties) {
        this.repository = repository;
        this.properties = properties.getGmail();
    }

    public record Claim(EmailDraftRequest entry, boolean alreadySucceeded) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DraftConflictException.class)
    public Claim claim(UUID userId, String key, String requestHash, UUID accountId, UUID applicationId) {
        EmailDraftRequest existing = repository.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
        if (existing == null) {
            EmailDraftRequest entry = new EmailDraftRequest();
            entry.setUserId(userId);
            entry.setIdempotencyKey(key);
            entry.setRequestHash(requestHash);
            entry.setGmailAccountId(accountId);
            entry.setApplicationId(applicationId);
            entry.setStatus(Status.PENDING);
            try {
                return new Claim(repository.saveAndFlush(entry), false);
            } catch (DataIntegrityViolationException race) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw new DraftConflictException("EMAIL_DRAFT_IN_PROGRESS",
                        "An identical draft request is already being processed. Check Gmail drafts before retrying.");
            }
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new DraftConflictException("EMAIL_DRAFT_KEY_REUSED",
                    "idempotencyKey was already used for a different draft. Use a new key for different content.");
        }
        return switch (existing.getStatus()) {
            case SUCCEEDED -> new Claim(existing, true);
            case FAILED -> {
                existing.setStatus(Status.PENDING);
                existing.setErrorMessage(null);
                yield new Claim(repository.saveAndFlush(existing), false);
            }
            case PENDING -> {
                if (existing.getUpdatedAt().isAfter(LocalDateTime.now().minus(properties.getPendingTimeout()))) {
                    throw new DraftConflictException("EMAIL_DRAFT_IN_PROGRESS",
                            "This draft is still being created. Wait a moment and check Gmail drafts.");
                }
                existing.setStatus(Status.UNKNOWN);
                repository.saveAndFlush(existing);
                throw ambiguous();
            }
            case UNKNOWN -> throw ambiguous();
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID entryId, String draftId, String messageId) {
        repository.findById(entryId).ifPresent(entry -> {
            entry.setStatus(Status.SUCCEEDED);
            entry.setGmailDraftId(draftId);
            entry.setGmailMessageId(messageId);
            entry.setErrorMessage(null);
            repository.save(entry);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID entryId, String error) {
        update(entryId, Status.FAILED, error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(UUID entryId, String error) {
        update(entryId, Status.UNKNOWN, error);
    }

    private void update(UUID entryId, Status status, String error) {
        repository.findById(entryId).ifPresent(entry -> {
            entry.setStatus(status);
            entry.setErrorMessage(error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)));
            repository.save(entry);
        });
    }

    static DraftConflictException ambiguous() {
        return new DraftConflictException("EMAIL_DRAFT_OUTCOME_UNKNOWN",
                "A previous attempt with the same content may already have created this draft. "
                        + "Ask the user to check Gmail drafts; to create another one anyway, retry with a new idempotencyKey.");
    }

    public static class DraftConflictException extends ConflictException implements CodedException {
        private final String code;

        public DraftConflictException(String code, String message) {
            super(message);
            this.code = code;
        }

        @Override
        public String getCode() {
            return code;
        }
    }
}
