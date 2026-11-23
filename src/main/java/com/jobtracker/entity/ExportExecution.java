package com.jobtracker.entity;

import com.jobtracker.entity.enums.ExportDestinationType;
import com.jobtracker.entity.enums.ExportExecutionStatus;
import com.jobtracker.entity.enums.ExportFormat;
import com.jobtracker.entity.enums.ExportTrigger;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit record of a single export attempt — manual download, scheduled run or run-now.
 *
 * <p>{@code errorMessage} always holds a sanitized message: no stack traces, no request payloads
 * and no credentials.
 */
@Entity
@Table(name = "export_executions", indexes = {
        @Index(name = "idx_export_executions_user", columnList = "user_id,started_at"),
        @Index(name = "idx_export_executions_schedule", columnList = "schedule_id")
})
public class ExportExecution {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** Null for manual downloads, which are not tied to a schedule. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private ExportSchedule schedule;

    @Column(name = "schedule_name", length = 120)
    private String scheduleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private ExportTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 20)
    private ExportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination", length = 30)
    private ExportDestinationType destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExportExecutionStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "record_count")
    private Integer recordCount;

    /** True when the record limit capped the export before all matching rows were written. */
    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_id", length = 255)
    private String fileId;

    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public ExportSchedule getSchedule() { return schedule; }
    public void setSchedule(ExportSchedule schedule) { this.schedule = schedule; }

    public String getScheduleName() { return scheduleName; }
    public void setScheduleName(String scheduleName) { this.scheduleName = scheduleName; }

    public ExportTrigger getTrigger() { return trigger; }
    public void setTrigger(ExportTrigger trigger) { this.trigger = trigger; }

    public ExportFormat getFormat() { return format; }
    public void setFormat(ExportFormat format) { this.format = format; }

    public ExportDestinationType getDestination() { return destination; }
    public void setDestination(ExportDestinationType destination) { this.destination = destination; }

    public ExportExecutionStatus getStatus() { return status; }
    public void setStatus(ExportExecutionStatus status) { this.status = status; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }

    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
