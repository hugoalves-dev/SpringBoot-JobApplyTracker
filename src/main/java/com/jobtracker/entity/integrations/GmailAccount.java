package com.jobtracker.entity.integrations;

import com.jobtracker.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/** A Gmail mailbox a user authorized for draft creation, labelled with a user-chosen profile (e.g. "Java"). */
@Entity
@Table(name = "gmail_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_gmail_accounts_user_email", columnNames = {"user_id", "email"}),
        @UniqueConstraint(name = "uk_gmail_accounts_user_profile", columnNames = {"user_id", "profile_name"})
})
public class GmailAccount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "profile_name", nullable = false, length = 60)
    private String profileName;

    @Column(name = "access_token_encrypted", nullable = false, length = 8192)
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", nullable = false, length = 4096)
    private String refreshTokenEncrypted;

    @Column(name = "access_token_expires_at", nullable = false)
    private LocalDateTime accessTokenExpiresAt;

    @Column(name = "granted_scopes", nullable = false, length = 2048)
    private String grantedScopes;

    @Column(name = "reauthorization_required", nullable = false)
    private boolean reauthorizationRequired;

    @Column(name = "last_tested_at")
    private LocalDateTime lastTestedAt;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (connectedAt == null) {
            connectedAt = createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getProfileName() { return profileName; }
    public void setProfileName(String profileName) { this.profileName = profileName; }
    public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
    public void setAccessTokenEncrypted(String accessTokenEncrypted) { this.accessTokenEncrypted = accessTokenEncrypted; }
    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public void setRefreshTokenEncrypted(String refreshTokenEncrypted) { this.refreshTokenEncrypted = refreshTokenEncrypted; }
    public LocalDateTime getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public void setAccessTokenExpiresAt(LocalDateTime accessTokenExpiresAt) { this.accessTokenExpiresAt = accessTokenExpiresAt; }
    public String getGrantedScopes() { return grantedScopes; }
    public void setGrantedScopes(String grantedScopes) { this.grantedScopes = grantedScopes; }
    public boolean isReauthorizationRequired() { return reauthorizationRequired; }
    public void setReauthorizationRequired(boolean reauthorizationRequired) { this.reauthorizationRequired = reauthorizationRequired; }
    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public LocalDateTime getConnectedAt() { return connectedAt; }
    public void setConnectedAt(LocalDateTime connectedAt) { this.connectedAt = connectedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
