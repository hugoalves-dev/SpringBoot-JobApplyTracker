package com.jobtracker.entity;

import com.jobtracker.entity.enums.BaseInformationDocType;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A "base information about me" document hosted on the user's Google Drive.
 *
 * <p>These documents (Google Docs, PDF, DOCX, or Markdown) describe the candidate and are the
 * authoritative, highest-priority source AIs read before generating any CV content. They are
 * always read-only knowledge sources — they are never copied into application folders nor used
 * for placeholder generation (that is the role of {@link GoogleDriveBaseResume}).
 */
@Entity
@Table(name = "google_drive_base_information", indexes = {
        @Index(name = "idx_gdrive_base_info_connection", columnList = "connection_id"),
        @Index(name = "idx_gdrive_base_info_file", columnList = "google_file_id"),
        @Index(name = "uk_gdrive_base_info_connection_file", columnList = "connection_id,google_file_id", unique = true)
})
public class GoogleDriveBaseInformation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GoogleDriveConnection connection;

    @Column(name = "google_file_id", nullable = false, length = 255)
    private String googleFileId;

    @Column(name = "document_name", nullable = false, length = 255)
    private String documentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private BaseInformationDocType docType;

    @Column(name = "web_view_link", length = 2048)
    private String webViewLink;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
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

    public GoogleDriveConnection getConnection() {
        return connection;
    }

    public void setConnection(GoogleDriveConnection connection) {
        this.connection = connection;
    }

    public String getGoogleFileId() {
        return googleFileId;
    }

    public void setGoogleFileId(String googleFileId) {
        this.googleFileId = googleFileId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public BaseInformationDocType getDocType() {
        return docType;
    }

    public void setDocType(BaseInformationDocType docType) {
        this.docType = docType;
    }

    public String getWebViewLink() {
        return webViewLink;
    }

    public void setWebViewLink(String webViewLink) {
        this.webViewLink = webViewLink;
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
}
