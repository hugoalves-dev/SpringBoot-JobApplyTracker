package com.jobtracker.entity.integrations;

import com.jobtracker.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tinyfetch_settings", indexes = {
        @Index(name = "uk_tinyfetch_settings_user", columnList = "user_id", unique = true)
})
public class TinyFetchSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** AES-GCM ciphertext produced by SecretCipher; never the raw key. */
    @Column(name = "api_key_encrypted", nullable = false, length = 2048)
    private String apiKeyEncrypted;

    @Column(name = "api_key_hint", length = 16)
    private String apiKeyHint;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "per_minute_limit", nullable = false)
    private int perMinuteLimit;

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit;

    @Column(name = "last_tested_at")
    private LocalDateTime lastTestedAt;

    @Column(name = "last_test_success")
    private Boolean lastTestSuccess;

    @Column(name = "last_test_message", length = 255)
    private String lastTestMessage;

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
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }
    public String getApiKeyHint() { return apiKeyHint; }
    public void setApiKeyHint(String apiKeyHint) { this.apiKeyHint = apiKeyHint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPerMinuteLimit() { return perMinuteLimit; }
    public void setPerMinuteLimit(int perMinuteLimit) { this.perMinuteLimit = perMinuteLimit; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public Boolean getLastTestSuccess() { return lastTestSuccess; }
    public void setLastTestSuccess(Boolean lastTestSuccess) { this.lastTestSuccess = lastTestSuccess; }
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
