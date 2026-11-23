package com.jobtracker.service;

import com.jobtracker.dto.gdrive.BaseInformationContentResponse;
import com.jobtracker.dto.gdrive.BaseInformationRequest;
import com.jobtracker.dto.gdrive.BaseInformationResponse;
import com.jobtracker.entity.GoogleDriveBaseInformation;
import com.jobtracker.entity.GoogleDriveConnection;
import com.jobtracker.entity.enums.BaseInformationDocType;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ResourceNotFoundException;
import com.jobtracker.repository.GoogleDriveBaseInformationRepository;
import com.jobtracker.util.GoogleDriveFileIds;
import com.jobtracker.util.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Manages "base information about me" documents stored on the user's Google Drive and extracts their
 * plain-text content. These documents are the authoritative, highest-priority source AIs read about
 * the candidate before generating any CV content.
 */
@Service
public class BaseInformationService {

    private final GoogleDriveApiClient googleDriveApiClient;
    private final GoogleDriveCredentialService credentialService;
    private final GoogleDriveBaseInformationRepository baseInformationRepository;
    private final DocumentTextExtractor documentTextExtractor;
    private final SecurityUtils securityUtils;

    public BaseInformationService(GoogleDriveApiClient googleDriveApiClient,
                                  GoogleDriveCredentialService credentialService,
                                  GoogleDriveBaseInformationRepository baseInformationRepository,
                                  DocumentTextExtractor documentTextExtractor,
                                  SecurityUtils securityUtils) {
        this.googleDriveApiClient = googleDriveApiClient;
        this.credentialService = credentialService;
        this.baseInformationRepository = baseInformationRepository;
        this.documentTextExtractor = documentTextExtractor;
        this.securityUtils = securityUtils;
    }

    @Transactional
    public BaseInformationResponse addBaseInformation(BaseInformationRequest request) {
        UUID userId = securityUtils.getCurrentUserId();
        GoogleDriveConnection connection = credentialService.getValidCredentials(userId);
        String documentId = GoogleDriveFileIds.extract(request.documentIdOrUrl());

        GoogleDriveApiClient.DriveFileMetadata file = credentialService.call(userId,
                token -> googleDriveApiClient.getFileMetadata(token, documentId));
        BaseInformationDocType docType = BaseInformationDocType.fromDriveFile(file.mimeType(), file.name());

        GoogleDriveBaseInformation info = baseInformationRepository
                .findByConnectionIdAndGoogleFileId(connection.getId(), file.id())
                .orElseGet(GoogleDriveBaseInformation::new);

        info.setConnection(connection);
        info.setGoogleFileId(file.id());
        info.setDocumentName(file.name());
        info.setDocType(docType);
        info.setWebViewLink(resolveDocumentLink(file));
        try {
            GoogleDriveBaseInformation saved = baseInformationRepository.save(info);
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert on the same (connection_id, google_file_id) – return existing
            return baseInformationRepository.findByConnectionIdAndGoogleFileId(connection.getId(), file.id())
                    .map(this::toResponse)
                    .orElseThrow(() -> new BadRequestException("Failed to save base information"));
        }
    }

    @Transactional(readOnly = true)
    public List<BaseInformationResponse> listBaseInformation() {
        UUID userId = securityUtils.getCurrentUserId();
        return baseInformationRepository.findAllByConnectionUserIdOrderByCreatedAtAsc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteBaseInformation(UUID baseInformationId) {
        UUID userId = securityUtils.getCurrentUserId();
        GoogleDriveBaseInformation info = baseInformationRepository.findByIdAndConnectionUserId(baseInformationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Base information not found with id: " + baseInformationId));
        baseInformationRepository.delete(info);
    }

    @Transactional
    public BaseInformationContentResponse getBaseInformationContent(UUID baseInformationId) {
        UUID userId = securityUtils.getCurrentUserId();
        credentialService.getValidCredentials(userId);
        GoogleDriveBaseInformation info = baseInformationRepository.findByIdAndConnectionUserId(baseInformationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Base information not found with id: " + baseInformationId));

        String content = readContent(userId, info.getDocType(), info.getGoogleFileId());

        return new BaseInformationContentResponse(
                info.getId(),
                info.getDocumentName(),
                info.getDocType().name(),
                content
        );
    }

    /**
     * Reads a base information document's plain text for an explicit user, without relying on the
     * request's security context (used by background embedding sync).
     */
    public String readContent(UUID userId, BaseInformationDocType docType, String googleFileId) {
        return switch (docType) {
            case GOOGLE_DOC -> credentialService.call(userId,
                    token -> googleDriveApiClient.readGoogleDocText(token, googleFileId));
            case PDF -> documentTextExtractor.extractPdf(credentialService.call(userId,
                    token -> googleDriveApiClient.downloadFileBytes(token, googleFileId)));
            case DOCX -> documentTextExtractor.extractDocx(credentialService.call(userId,
                    token -> googleDriveApiClient.downloadFileBytes(token, googleFileId)));
            case MARKDOWN -> documentTextExtractor.extractMarkdown(credentialService.call(userId,
                    token -> googleDriveApiClient.downloadFileBytes(token, googleFileId)));
        };
    }

    private BaseInformationResponse toResponse(GoogleDriveBaseInformation info) {
        return new BaseInformationResponse(
                info.getId(),
                info.getDocumentName(),
                info.getDocType().name(),
                info.getWebViewLink(),
                info.getCreatedAt()
        );
    }

    private String resolveDocumentLink(GoogleDriveApiClient.DriveFileMetadata file) {
        return StringUtils.hasText(file.webViewLink())
                ? file.webViewLink()
                : "https://drive.google.com/file/d/" + file.id() + "/view";
    }
}
