package com.jobtracker.unit;

import com.jobtracker.config.GoogleDriveProperties;
import com.jobtracker.dto.gdrive.GoogleDriveBaseResumeRequest;
import com.jobtracker.dto.gdrive.GoogleDriveResumeCopyRequest;
import com.jobtracker.dto.gdrive.GoogleDriveRootFolderRequest;
import com.jobtracker.dto.gdrive.GoogleDriveStatusResponse;
import com.jobtracker.entity.GoogleDriveBaseResume;
import com.jobtracker.entity.GoogleDriveConnection;
import com.jobtracker.entity.JobApplication;
import com.jobtracker.entity.User;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.GoogleDriveBaseResumeRepository;
import com.jobtracker.repository.GoogleDriveConnectionRepository;
import com.jobtracker.service.GoogleDriveApiClient;
import com.jobtracker.service.GoogleDriveConnectionStateWriter;
import com.jobtracker.service.GoogleDriveCredentialService;
import com.jobtracker.service.GoogleDriveService;
import com.jobtracker.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleDriveServiceTest {

    private static final String TEST_SCOPES = "https://www.googleapis.com/auth/drive,https://www.googleapis.com/auth/documents.readonly";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONNECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RESUME_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID APPLICATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Mock private GoogleDriveApiClient googleDriveApiClient;
    @Mock private GoogleDriveConnectionRepository connectionRepository;
    @Mock private GoogleDriveBaseResumeRepository baseResumeRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private SecurityUtils securityUtils;

    private GoogleDriveProperties googleDriveProperties;

    private GoogleDriveService googleDriveService;

    private GoogleDriveConnection connection;
    private GoogleDriveBaseResume baseResume;
    private JobApplication application;

    @BeforeEach
    void setUp() {
        googleDriveProperties = new GoogleDriveProperties(
                "client-id",
                "client-secret",
                "http://localhost:8080/api/v1/google-drive/oauth/callback",
                "http://localhost:5173/settings/google-drive/callback",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                TEST_SCOPES,
                60
        );
        GoogleDriveCredentialService credentialService = new GoogleDriveCredentialService(
                googleDriveApiClient, googleDriveProperties, connectionRepository,
                new GoogleDriveConnectionStateWriter(connectionRepository));
        googleDriveService = new GoogleDriveService(
                googleDriveApiClient,
                credentialService,
                googleDriveProperties,
                connectionRepository,
                baseResumeRepository,
                applicationRepository,
                securityUtils
        );

        User user = new User();
        user.setId(USER_ID);
        user.setEmail("user@example.com");

        connection = new GoogleDriveConnection();
        connection.setId(CONNECTION_ID);
        connection.setUser(user);
        connection.setGoogleAccountId("perm-1");
        connection.setGoogleEmail("drive@example.com");
        connection.setAccessToken("access-token");
        connection.setRefreshToken("refresh-token");
        connection.setAccessTokenExpiresAt(LocalDateTime.now().plusHours(1));
        connection.setGrantedScopes("https://www.googleapis.com/auth/drive");

        baseResume = new GoogleDriveBaseResume();
        baseResume.setId(RESUME_ID);
        baseResume.setConnection(connection);
        baseResume.setGoogleFileId("resume-file-id");
        baseResume.setDocumentName("Base Resume");
        baseResume.setWebViewLink("https://docs.google.com/document/d/resume-file-id/edit");
        baseResume.setCreatedAt(LocalDateTime.now());

        application = new JobApplication();
        application.setId(APPLICATION_ID);
        application.setVacancyName("Backend Engineer");
        application.setOrganization("Acme");
        application.setApplicationDate(LocalDate.now());
        application.setUser(user);
    }

    @Test
    void getStatus_shouldReturnDisconnected_whenNoConnectionExists() {
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(connectionRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        GoogleDriveStatusResponse response = googleDriveService.getStatus();

        assertThat(response.configured()).isTrue();
        assertThat(response.connected()).isFalse();
        assertThat(response.baseResumes()).isEmpty();
    }

    @Test
    void updateRootFolder_shouldPersistFolderMetadata() {
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(connectionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(connection));
        when(googleDriveApiClient.getFileMetadata("access-token", "root-folder-id"))
                .thenReturn(new GoogleDriveApiClient.DriveFileMetadata(
                        "root-folder-id",
                        "Job Tracker Root",
                        GoogleDriveApiClient.GOOGLE_FOLDER_MIME_TYPE,
                        "https://drive.google.com/drive/folders/root-folder-id"
                ));
        when(baseResumeRepository.findAllByConnectionIdOrderByCreatedAtAsc(CONNECTION_ID)).thenReturn(List.of());

        GoogleDriveStatusResponse response = googleDriveService.updateRootFolder(new GoogleDriveRootFolderRequest("root-folder-id"));

        assertThat(response.rootFolderId()).isEqualTo("root-folder-id");
        assertThat(response.rootFolderName()).isEqualTo("Job Tracker Root");
        verify(connectionRepository).save(connection);
    }

    @Test
    void addBaseResume_shouldRejectUnsupportedFileTypes() {
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(connectionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(connection));
        when(googleDriveApiClient.getFileMetadata("access-token", "not-a-doc"))
                .thenReturn(new GoogleDriveApiClient.DriveFileMetadata(
                        "not-a-doc",
                        "Resume.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        null
                ));

        assertThatThrownBy(() -> googleDriveService.addBaseResume(new GoogleDriveBaseResumeRequest("not-a-doc", null, false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only Google Docs documents and PDF files are supported");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "Backend Engineer,Acme,Backend Engineer",
            "NULL,Acme,Acme",
            "'',Acme,Acme",
            "NULL,NULL,Application"
    }, nullValues = "NULL")
    void copyBaseResumeToApplication_shouldCreateFolderAndCopyDocument(
            String vacancyName, String organization, String expectedName) {
        application.setVacancyName(vacancyName);
        application.setOrganization(organization);
        connection.setRootFolderId("root-folder-id");
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(connectionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(connection));
        when(applicationRepository.findByIdAndUserId(APPLICATION_ID, USER_ID)).thenReturn(Optional.of(application));
        when(baseResumeRepository.findByIdAndConnectionUserId(RESUME_ID, USER_ID)).thenReturn(Optional.of(baseResume));
        when(googleDriveApiClient.getFileMetadata("access-token", "root-folder-id"))
                .thenReturn(new GoogleDriveApiClient.DriveFileMetadata(
                        "root-folder-id",
                        "Job Tracker Root",
                        GoogleDriveApiClient.GOOGLE_FOLDER_MIME_TYPE,
                        "https://drive.google.com/drive/folders/root-folder-id"
                ));
        when(googleDriveApiClient.findFolderByName(eq("access-token"), eq("root-folder-id"), contains("APP-")))
                .thenReturn(Optional.empty());
        when(googleDriveApiClient.createFolder(eq("access-token"), eq("root-folder-id"), contains("APP-")))
                .thenReturn(new GoogleDriveApiClient.DriveFileMetadata(
                        "vacancy-folder-id",
                        "Backend Engineer - APP-" + APPLICATION_ID,
                        GoogleDriveApiClient.GOOGLE_FOLDER_MIME_TYPE,
                        "https://drive.google.com/drive/folders/vacancy-folder-id"
                ));
        when(googleDriveApiClient.copyGoogleDoc(eq("access-token"), eq("resume-file-id"), eq("vacancy-folder-id"), contains("APP-")))
                .thenReturn(new GoogleDriveApiClient.DriveFileMetadata(
                        "copied-doc-id",
                        "APP-" + APPLICATION_ID + " - Backend Engineer - Base Resume",
                        GoogleDriveApiClient.GOOGLE_DOC_MIME_TYPE,
                        "https://docs.google.com/document/d/copied-doc-id/edit"
                ));

        var response = googleDriveService.copyBaseResumeToApplication(APPLICATION_ID, new GoogleDriveResumeCopyRequest(RESUME_ID));

        assertThat(response.applicationId()).isEqualTo(APPLICATION_ID);
        assertThat(response.baseResumeId()).isEqualTo(RESUME_ID);
        assertThat(response.copiedFileId()).isEqualTo("copied-doc-id");
        assertThat(response.documentWebViewLink()).contains("copied-doc-id");
        assertThat(response.generatedAt()).isNotNull();
        assertThat(application.getDriveResumeFileId()).isEqualTo("copied-doc-id");
        assertThat(application.getDriveResumeFileName()).contains("APP-" + APPLICATION_ID);
        assertThat(application.getDriveResumeDocumentUrl()).contains("copied-doc-id");
        assertThat(application.getDriveResumeGeneratedAt()).isNotNull();

        ArgumentCaptor<String> folderNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(googleDriveApiClient).createFolder(eq("access-token"), eq("root-folder-id"), folderNameCaptor.capture());
        verify(applicationRepository).save(application);
        assertThat(folderNameCaptor.getValue()).isEqualTo(expectedName + " - APP-" + APPLICATION_ID);
    }
}