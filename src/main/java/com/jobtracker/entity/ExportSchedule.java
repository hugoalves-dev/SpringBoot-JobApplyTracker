package com.jobtracker.entity;

import com.jobtracker.entity.enums.ExportDestinationType;
import com.jobtracker.entity.enums.ExportFormat;
import com.jobtracker.entity.enums.ExportFrequency;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A user-owned recurring export configuration.
 *
 * <p>No cron expression supplied by the user is ever persisted: the recurrence is stored as a
 * validated domain configuration (frequency + time of day + day of week/month + timezone) and the
 * next execution instant is derived from it by
 * {@link com.jobtracker.service.export.ExportScheduleService}.
 *
 * <p>{@code nextRunAt}, {@code lastRunAt} and {@code runningSince} are stored in <strong>UTC</strong>
 * so that schedules from different timezones can be compared with a single query.
 */
@Entity
@Table(name = "export_schedules", indexes = {
        @Index(name = "idx_export_schedules_user", columnList = "user_id"),
        @Index(name = "idx_export_schedules_next_run", columnList = "enabled,next_run_at")
})
public class ExportSchedule {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 20)
    private ExportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private ExportFrequency frequency;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    /** ISO day of week (1 = Monday … 7 = Sunday). Only set for {@link ExportFrequency#WEEKLY}. */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** Day of month (1–28, so every month has it). Only set for {@link ExportFrequency#MONTHLY}. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false, length = 30)
    private ExportDestinationType destination;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Serialized {@link com.jobtracker.dto.export.ExportFilters}. */
    @Column(name = "filters_json", columnDefinition = "TEXT")
    private String filtersJson;

    /** Serialized list of {@link com.jobtracker.service.export.ExportColumn} names; null = all columns. */
    @Column(name = "columns_json", columnDefinition = "TEXT")
    private String columnsJson;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** Execution lock, claimed with a conditional UPDATE so only one instance can run a schedule. */
    @Column(name = "running", nullable = false)
    private boolean running;

    @Column(name = "running_since")
    private LocalDateTime runningSince;

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
    public void setId(UUID id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ExportFormat getFormat() { return format; }
    public void setFormat(ExportFormat format) { this.format = format; }

    public ExportFrequency getFrequency() { return frequency; }
    public void setFrequency(ExportFrequency frequency) { this.frequency = frequency; }

    public LocalTime getTimeOfDay() { return timeOfDay; }
    public void setTimeOfDay(LocalTime timeOfDay) { this.timeOfDay = timeOfDay; }

    public Integer getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Integer dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public ExportDestinationType getDestination() { return destination; }
    public void setDestination(ExportDestinationType destination) { this.destination = destination; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }

    public String getColumnsJson() { return columnsJson; }
    public void setColumnsJson(String columnsJson) { this.columnsJson = columnsJson; }

    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public LocalDateTime getRunningSince() { return runningSince; }
    public void setRunningSince(LocalDateTime runningSince) { this.runningSince = runningSince; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
