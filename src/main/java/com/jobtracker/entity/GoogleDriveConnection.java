package com.jobtracker.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "google_drive_connections", indexes = {
        @Index(name = "idx_gdrive_connection_user", columnList = "user_id", unique = true),
        @Index(name = "idx_gdrive_connection_google_account", columnList = "google_account_id")
})
public class GoogleDriveConnection {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Mirrors the ON DELETE CASCADE the migration declares, so deleting a user drops the connection. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "google_account_id", nullable = false, length = 255)
    private String googleAccountId;

    @Column(name = "google_email", nullable = false, length = 255)
    private String googleEmail;

    @Column(name = "google_display_name", length = 255)
    private String googleDisplayName;

    @Column(name = "access_token", nullable = false, length = 4096)
    private String accessToken;

    @Column(name = "refresh_token", nullable = false, length = 4096)
    private String refreshToken;

    @Column(name = "access_token_expires_at", nullable = false)
    private LocalDateTime accessTokenExpiresAt;

    @Column(name = "granted_scopes", nullable = false, length = 2048)
    private String grantedScopes;

    @Column(name = "root_folder_id", length = 255)
    private String rootFolderId;

    @Column(name = "root_folder_name", length = 255)
    private String rootFolderName;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    @OneToMany(mappedBy = "connection", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GoogleDriveBaseResume> baseResumes = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Set only when a token refresh attempt proves the stored refresh token can no longer be
     * used (e.g. Google returned {@code invalid_grant}), so callers stop retrying refreshes and
     * surface a reauthorization prompt instead. Cleared automatically on successful reconnect.
     */
    @Column(name = "reauthorization_required", nullable = false)
    private boolean reauthorizationRequired = false;

    @Column(name = "reauthorization_reason", length = 255)
    private String reauthorizationReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (connectedAt == null) {
            connectedAt = createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getGoogleAccountId() {
        return googleAccountId;
    }

    public void setGoogleAccountId(String googleAccountId) {
        this.googleAccountId = googleAccountId;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    public void setGoogleEmail(String googleEmail) {
        this.googleEmail = googleEmail;
    }

    public String getGoogleDisplayName() {
        return googleDisplayName;
    }

    public void setGoogleDisplayName(String googleDisplayName) {
        this.googleDisplayName = googleDisplayName;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public LocalDateTime getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public void setAccessTokenExpiresAt(LocalDateTime accessTokenExpiresAt) {
        this.accessTokenExpiresAt = accessTokenExpiresAt;
    }

    public String getGrantedScopes() {
        return grantedScopes;
    }

    public void setGrantedScopes(String grantedScopes) {
        this.grantedScopes = grantedScopes;
    }

    public String getRootFolderId() {
        return rootFolderId;
    }

    public void setRootFolderId(String rootFolderId) {
        this.rootFolderId = rootFolderId;
    }

    public String getRootFolderName() {
        return rootFolderName;
    }

    public void setRootFolderName(String rootFolderName) {
        this.rootFolderName = rootFolderName;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(LocalDateTime connectedAt) {
        this.connectedAt = connectedAt;
    }

    public List<GoogleDriveBaseResume> getBaseResumes() {
        return baseResumes;
    }

    public void setBaseResumes(List<GoogleDriveBaseResume> baseResumes) {
        this.baseResumes = baseResumes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isReauthorizationRequired() {
        return reauthorizationRequired;
    }

    public void setReauthorizationRequired(boolean reauthorizationRequired) {
        this.reauthorizationRequired = reauthorizationRequired;
    }

    public String getReauthorizationReason() {
        return reauthorizationReason;
    }

    public void setReauthorizationReason(String reauthorizationReason) {
        this.reauthorizationReason = reauthorizationReason;
    }
}
