package com.jobtracker.service;

import com.jobtracker.config.GoogleDriveProperties;
import com.jobtracker.dto.gdrive.*;
import com.jobtracker.entity.GoogleDriveBaseResume;
import com.jobtracker.entity.GoogleDriveConnection;
import com.jobtracker.entity.JobApplication;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.GoogleDriveBaseResumeRepository;
import com.jobtracker.repository.GoogleDriveConnectionRepository;
import com.jobtracker.util.GoogleDriveFileIds;
import com.jobtracker.util.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class GoogleDriveService {

    private final GoogleDriveApiClient googleDriveApiClient;
    private final GoogleDriveCredentialService credentialService;
    private final GoogleDriveProperties googleDriveProperties;
    private final GoogleDriveConnectionRepository connectionRepository;
    private final GoogleDriveBaseResumeRepository baseResumeRepository;
    private final ApplicationRepository applicationRepository;
    private final SecurityUtils securityUtils;

    public GoogleDriveService(GoogleDriveApiClient googleDriveApiClient,
                              GoogleDriveCredentialService credentialService,
                              GoogleDriveProperties googleDriveProperties,
                              GoogleDriveConnectionRepository connectionRepository,
                              GoogleDriveBaseResumeRepository baseResumeRepository,
                              ApplicationRepository applicationRepository,
                              SecurityUtils securityUtils) {
        this.googleDriveApiClient = googleDriveApiClient;
        this.credentialService = credentialService;
        this.googleDriveProperties = googleDriveProperties;
        this.connectionRepository = connectionRepository;
        this.baseResumeRepository = baseResumeRepository;
        this.applicationRepository = applicationRepository;
        this.securityUtils = securityUtils;
    }

    @Transactional(readOnly = true)
    public GoogleDriveStatusResponse getStatus() {
        if (!googleDriveProperties.isConfigured()) {
            return new GoogleDriveStatusResponse(false, false, null, null, null, null, null, null, List.of());
        }

        Optional<GoogleDriveConnection> connectionOptional = connectionRepository.findByUserId(securityUtils.getCurrentUserId());
        if (connectionOptional.isEmpty()) {
            return new GoogleDriveStatusResponse(true, false, null, null, null, null, null, null, List.of());
        }

        return toStatusResponse(connectionOptional.get());
    }

    @Transactional
    public GoogleDriveStatusResponse updateRootFolder(GoogleDriveRootFolderRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        GoogleDriveConnection connection = credentialService.getValidCredentials(userId);
        String folderId = GoogleDriveFileIds.extract(request.folderIdOrUrl());

        GoogleDriveApiClient.DriveFileMetadata folder = credentialService.call(userId,
                token -> googleDriveApiClient.getFileMetadata(token, folderId));
        if (!GoogleDriveApiClient.GOOGLE_FOLDER_MIME_TYPE.equals(folder.mimeType())) {
            throw new BadRequestException("Configured root folder must be a Google Drive folder");
        }

        connection.setRootFolderId(folder.id());
        connection.setRootFolderName(folder.name());
        connectionRepository.save(connection);
        return toStatusResponse(connection);
    }

    @Transactional
    public GoogleDriveBaseResumeResponse addBaseResume(GoogleDriveBaseResumeRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        GoogleDriveConnection connection = credentialService.getValidCredentials(userId);
        String documentId = GoogleDriveFileIds.extract(request.documentIdOrUrl());

        GoogleDriveApiClient.DriveFileMetadata file = credentialService.call(userId,
                token -> googleDriveApiClient.getFileMetadata(token, documentId));
        if (!GoogleDriveApiClient.GOOGLE_DOC_MIME_TYPE.equals(file.mimeType()) &&
                !GoogleDriveApiClient.PDF_MIME_TYPE.equals(file.mimeType())) {
            throw new BadRequestException("Only Google Docs documents and PDF files are supported as base resumes");
        }

        boolean isReadOnly = GoogleDriveApiClient.PDF_MIME_TYPE.equals(file.mimeType());

        GoogleDriveBaseResume resume = baseResumeRepository
                .findByConnectionIdAndGoogleFileId(connection.getId(), file.id())
                .orElseGet(GoogleDriveBaseResume::new);

        resume.setConnection(connection);
        resume.setGoogleFileId(file.id());
        resume.setDocumentName(file.name());
        resume.setWebViewLink(resolveDocumentLink(file));
        resume.setLanguage(request.language());
        resume.setTemplate(!isReadOnly && request.template());
        resume.setReadOnly(isReadOnly);
        try {
            GoogleDriveBaseResume saved = baseResumeRepository.save(resume);
            return toBaseResumeResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert on the same (connection_id, google_file_id) – return existing
            return baseResumeRepository.findByConnectionIdAndGoogleFileId(connection.getId(), file.id())
                    .map(this::toBaseResumeResponse)
                    .orElseThrow(() -> new BadRequestException("Failed to save base resume"));
        }
    }

    @Transactional
    public void deleteBaseResume(UUID baseResumeId) {
        UUID userId = securityUtils.getCurrentUserId();
        GoogleDriveBaseResume resume = baseResumeRepository.findByIdAndConnectionUserId(baseResumeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Base resume not found with id: " + baseResumeId));
        baseResumeRepository.delete(resume);
    }

    @Transactional(readOnly = true)
    public List<BaseResumeResponse> listBaseResumes() {
        UUID userId = securityUtils.getCurrentUserId();
        return baseResumeRepository.findAllByConnectionUserIdOrderByCreatedAtAsc(userId)
                .stream()
                .map(this::toBaseResumeListingResponse)
                .toList();
    }

    @Transactional
    public GoogleDriveResumeCopyResponse copyBaseResumeToApplication(UUID applicationId, GoogleDriveResumeCopyRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        GoogleDriveConnection connection = credentialService.getValidCredentials(userId);
        JobApplication application = applicationRepository.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + applicationId));

        if (!StringUtils.hasText(connection.getRootFolderId())) {
            throw new BadRequestException("Configure a Google Drive root folder before copying resumes");
        }

        GoogleDriveBaseResume baseResume = baseResumeRepository.findByIdAndConnectionUserId(request.baseResumeId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Base resume not found with id: " + request.baseResumeId()));

        if (baseResume.isReadOnly()) {
            throw new BadRequestException("Cannot copy a read-only PDF resume to an application. Use a Google Docs template instead.");
        }

        GoogleDriveApiClient.DriveFileMetadata rootFolder = credentialService.call(userId,
                token -> googleDriveApiClient.getFileMetadata(token, connection.getRootFolderId()));
        if (!GoogleDriveApiClient.GOOGLE_FOLDER_MIME_TYPE.equals(rootFolder.mimeType())) {
            throw new BadRequestException("Configured root folder is no longer a valid Google Drive folder");
        }
        connection.setRootFolderName(rootFolder.name());

        // Use the stored Drive folder ID for this application when available, so that renaming
        // the vacancy/organization after the first copy still resolves to the correct folder.
        GoogleDriveApiClient.DriveFileMetadata vacancyFolder =
                resolveOrCreateVacancyFolder(userId, application, rootFolder.id());

        String copiedFileName = buildCopiedDocumentName(application, baseResume.getDocumentName());
        GoogleDriveApiClient.DriveFileMetadata copiedFile = credentialService.call(userId, token ->
                googleDriveApiClient.copyGoogleDoc(token, baseResume.getGoogleFileId(), vacancyFolder.id(), copiedFileName));
        String copiedDocumentUrl = resolveDocumentLink(copiedFile);
        LocalDateTime generatedAt = LocalDateTime.now();

        application.setDriveResumeFileId(copiedFile.id());
        application.setDriveResumeFileName(copiedFile.name());
        application.setDriveResumeDocumentUrl(copiedDocumentUrl);
        application.setDriveResumeGeneratedAt(generatedAt);

        connectionRepository.save(connection);
        applicationRepository.save(application);
        return new GoogleDriveResumeCopyResponse(
                application.getId(),
                baseResume.getId(),
                copiedFile.id(),
                copiedFile.name(),
                copiedDocumentUrl,
                vacancyFolder.id(),
                vacancyFolder.name(),
                resolveFolderLink(vacancyFolder.id(), vacancyFolder.webViewLink()),
                generatedAt
        );
    }

    /**
     * Resolves the Drive vacancy folder for an application, creating it if needed.
     *
     * <p>The folder ID is stored on the {@link JobApplication} row after the first copy so that
     * subsequent copies continue to use the same folder even if the vacancy name or organisation
     * is later edited. The ID is stored with a conditional UPDATE ({@code WHERE
     * drive_vacancy_folder_id IS NULL}) so that concurrent copy requests for the same application
     * race to store their folder ID; the loser discards the orphan folder it created and uses the
     * winner's ID instead.
     */
    private GoogleDriveApiClient.DriveFileMetadata resolveOrCreateVacancyFolder(
            UUID userId,
            JobApplication application,
            String rootFolderId) {

        // Fast path: use the stored folder ID (handles renames and removes the need for a name search)
        if (StringUtils.hasText(application.getDriveVacancyFolderId())) {
            return credentialService.call(userId,
                    token -> googleDriveApiClient.getFileMetadata(token, application.getDriveVacancyFolderId()));
        }

        // Slow path: search by name then create if not found
        String vacancyFolderName = buildVacancyFolderName(application);
        GoogleDriveApiClient.DriveFileMetadata folder = credentialService.call(userId, token ->
                googleDriveApiClient.findFolderByName(token, rootFolderId, vacancyFolderName)
                        .orElseGet(() -> googleDriveApiClient.createFolder(token, rootFolderId, vacancyFolderName)));

        // Attempt to atomically record the resolved folder ID; if a concurrent request beat us,
        // fetch the winning ID from the DB and use that folder instead.
        int updated = applicationRepository.setDriveVacancyFolderIdIfAbsent(application.getId(), folder.id());
        if (updated == 0) {
            String winningFolderId = applicationRepository.findByIdAndUserId(application.getId(), userId)
                    .map(JobApplication::getDriveVacancyFolderId)
                    .filter(StringUtils::hasText)
                    .orElse(folder.id()); // concurrent tx hasn't committed yet; use our own folder this time
            if (!winningFolderId.equals(folder.id())) {
                folder = credentialService.call(userId, token -> googleDriveApiClient.getFileMetadata(token, winningFolderId));
            }
        }

        return folder;
    }

    /**
     * Same as the request-scoped lookup, but for an explicit user — used by background jobs such as
     * scheduled exports, which run without a {@code SecurityContext}.
     */
    public GoogleDriveConnection getConnectionWithFreshAccessToken(UUID userId) {
        return credentialService.getValidCredentials(userId);
    }

    private GoogleDriveStatusResponse toStatusResponse(GoogleDriveConnection connection) {
        List<GoogleDriveBaseResumeResponse> resumes = baseResumeRepository.findAllByConnectionIdOrderByCreatedAtAsc(connection.getId())
                .stream()
                .sorted(Comparator.comparing(GoogleDriveBaseResume::getCreatedAt))
                .map(this::toBaseResumeResponse)
                .toList();
        return new GoogleDriveStatusResponse(
                true,
                true,
                connection.getGoogleEmail(),
                connection.getGoogleDisplayName(),
                connection.getGoogleAccountId(),
                connection.getRootFolderId(),
                connection.getRootFolderName(),
                connection.getConnectedAt(),
                resumes
        );
    }

    private GoogleDriveBaseResumeResponse toBaseResumeResponse(GoogleDriveBaseResume resume) {
        return new GoogleDriveBaseResumeResponse(
                resume.getId(),
                resume.getGoogleFileId(),
                resume.getDocumentName(),
                resume.getWebViewLink(),
                resume.isReadOnly(),
                resume.getCreatedAt()
        );
    }

    private BaseResumeResponse toBaseResumeListingResponse(GoogleDriveBaseResume resume) {
        return new BaseResumeResponse(
                resume.getId(),
                resume.getDocumentName(),
                resume.getLanguage(),
                resume.isTemplate(),
                resume.isReadOnly(),
                resume.getCreatedAt()
        );
    }

    private String buildVacancyFolderName(JobApplication application) {
        String suffix = " - APP-" + application.getId().toString();
        String rawBase = applicationDisplayName(application);
        String truncatedBase = truncateFileName(sanitizeFileName(rawBase), 180 - suffix.length());
        return truncatedBase + suffix;
    }

    private String buildCopiedDocumentName(JobApplication application, String baseResumeName) {
        String vacancyName = applicationDisplayName(application);
        String prefix = "APP-" + application.getId() + " - " + vacancyName;
        return truncateFileName(sanitizeFileName(prefix + " - " + baseResumeName), 220);
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", " ").trim();
    }

    private String truncateFileName(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength).trim();
    }

    private String applicationDisplayName(JobApplication application) {
        if (StringUtils.hasText(application.getVacancyName())) {
            return application.getVacancyName().trim();
        }
        if (StringUtils.hasText(application.getOrganization())) {
            return application.getOrganization().trim();
        }
        return "Application";
    }

    private String resolveDocumentLink(GoogleDriveApiClient.DriveFileMetadata file) {
        return StringUtils.hasText(file.webViewLink())
                ? file.webViewLink()
                : "https://docs.google.com/document/d/" + file.id() + "/edit";
    }

    private String resolveFolderLink(String folderId, String webViewLink) {
        return StringUtils.hasText(webViewLink)
                ? webViewLink
                : "https://drive.google.com/drive/folders/" + folderId;
    }
}
