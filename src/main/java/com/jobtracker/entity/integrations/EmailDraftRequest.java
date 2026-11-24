package com.jobtracker.entity.integrations;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Idempotency ledger for Gmail draft creation. One row per (user, idempotency key); the row is
 * written before calling Gmail so a retry after a timeout never silently creates a second draft.
 */
@Entity
@Table(name = "email_draft_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_email_draft_requests_user_key", columnNames = {"user_id", "idempotency_key"})
})
public class EmailDraftRequest {

    public enum Status { PENDING, SUCCEEDED, FAILED, UNKNOWN }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** SHA-256 of the normalized request, so a reused key with different content is rejected. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "gmail_account_id", nullable = false)
    private UUID gmailAccountId;

    @Column(name = "application_id")
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "gmail_draft_id", length = 255)
    private String gmailDraftId;

    @Column(name = "gmail_message_id", length = 255)
    private String gmailMessageId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public UUID getGmailAccountId() { return gmailAccountId; }
    public void setGmailAccountId(UUID gmailAccountId) { this.gmailAccountId = gmailAccountId; }
    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID applicationId) { this.applicationId = applicationId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getGmailDraftId() { return gmailDraftId; }
    public void setGmailDraftId(String gmailDraftId) { this.gmailDraftId = gmailDraftId; }
    public String getGmailMessageId() { return gmailMessageId; }
    public void setGmailMessageId(String gmailMessageId) { this.gmailMessageId = gmailMessageId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
