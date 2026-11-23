package com.jobtracker.entity;

import com.jobtracker.entity.enums.AssistantReservationStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/** Timestamps are stored as UTC wall-clock values, independent of the JVM time zone. */
@Entity
@Table(name = "assistant_request_reservations", indexes = {
        @Index(name = "idx_assistant_reservations_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_assistant_reservations_created", columnList = "created_at")
})
public class AssistantRequestReservation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssistantReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    protected AssistantRequestReservation() {}

    public AssistantRequestReservation(UUID userId, LocalDateTime createdAtUtc) {
        this.userId = userId;
        this.createdAt = createdAtUtc;
        this.status = AssistantReservationStatus.IN_PROGRESS;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public AssistantReservationStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
}
