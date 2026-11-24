package com.jobtracker.entity.integrations;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.UUID;

/** URLs billed against a user's TinyFish quota per UTC day (cache hits are not counted). */
@Entity
@Table(name = "tinyfetch_daily_usage", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tinyfetch_usage_user_day", columnNames = {"user_id", "usage_date"})
})
public class TinyFetchDailyUsage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "fetch_count", nullable = false)
    private int fetchCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    protected TinyFetchDailyUsage() {
    }

    public TinyFetchDailyUsage(UUID userId, LocalDate usageDate) {
        this.userId = userId;
        this.usageDate = usageDate;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public LocalDate getUsageDate() { return usageDate; }
    public int getFetchCount() { return fetchCount; }
    public void setFetchCount(int fetchCount) { this.fetchCount = fetchCount; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
}
