package com.jobtracker.integration;

import tools.jackson.databind.ObjectMapper;
import com.jobtracker.dto.application.ApplicationRequest;
import com.jobtracker.dto.auth.AuthResponse;
import com.jobtracker.dto.auth.RegisterRequest;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.InterviewEventRepository;
import com.jobtracker.repository.PasswordResetTokenRepository;
import com.jobtracker.repository.RefreshTokenRepository;
import com.jobtracker.repository.UserAchievementRepository;
import com.jobtracker.repository.UserGamificationRepository;
import com.jobtracker.repository.UserInterviewMetricsRepository;
import com.jobtracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicationControllerIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private InterviewEventRepository interviewEventRepository;
    @Autowired private UserGamificationRepository userGamificationRepository;
    @Autowired private UserAchievementRepository userAchievementRepository;
    @Autowired private UserInterviewMetricsRepository userInterviewMetricsRepository;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        userAchievementRepository.deleteAll();
        interviewEventRepository.deleteAll();
        userInterviewMetricsRepository.deleteAll();
        userGamificationRepository.deleteAll();
        applicationRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        RegisterRequest reg = new RegisterRequest("App User", "appuser@example.com", "pass1234", "pass1234", true);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andReturn();

        AuthResponse auth = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        accessToken = auth.accessToken();
    }

    @Test
    void createApplication_shouldReturn201() throws Exception {
        ApplicationRequest request = buildRequest("Software Engineer");

        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.vacancyName").value("Software Engineer"))
                .andExpect(jsonPath("$.status").value("RH"))
                .andExpect(jsonPath("$.note").value("Remember to follow up"));
    }

        @Test
        void createApplication_shouldAllowBlankVacancyName() throws Exception {
                ApplicationRequest request = buildRequest("   ");

                mockMvc.perform(post("/api/v1/applications")
                                                .header("Authorization", "Bearer " + accessToken)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").exists())
                                .andExpect(jsonPath("$.vacancyName").doesNotExist());
        }

    @Test
    void createApplication_shouldReturn403_whenNotAuthenticated() throws Exception {
        ApplicationRequest request = buildRequest("Backend Dev");

        mockMvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getById_shouldReturn200_whenFound() throws Exception {
        ApplicationRequest request = buildRequest("Senior Dev");
        MvcResult createResult = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void getById_shouldReturn404_whenNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/applications/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateApplication_shouldReturn200() throws Exception {
        ApplicationRequest create = buildRequest("Junior Dev");
        MvcResult createResult = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        ApplicationRequest update = buildRequest("Senior Dev Updated");
        mockMvc.perform(put("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vacancyName").value("Senior Dev Updated"));
    }

    @Test
    void updateStatus_shouldReturn200() throws Exception {
        ApplicationRequest create = buildRequest("Status Test");
        MvcResult createResult = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Technical Test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Technical Test"));
    }

    @Test
    void createAndReplace_preserveExplicitInterviewTotals() throws Exception {
        ApplicationRequest request = new ApplicationRequest(
                "Completed interviews", null, null, null, LocalDate.now().minusDays(1),
                false, false, null, "Pending HR Response", false, null, null, 2);
        MvcResult created = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.interviewCount").value(2))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        ApplicationRequest replacement = new ApplicationRequest(
                "Completed interviews", null, null, null, LocalDate.now().minusDays(1),
                false, false, null, "Pending Hiring Manager Response", false, null, null, 3);
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/v1/applications/{id}", id)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(replacement)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(3));
        }
        mockMvc.perform(put("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("Preserved count"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(3));
    }

    @Test
    void confirmedInterviewCount_isAbsoluteAndIndependentOfStatus() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("Interview Counter"))))
                .andExpect(status().isCreated()).andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Technical Test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(0));

        // Explicit count and status in the same request must not add an implicit interview.
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/api/v1/applications/{id}", id)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"Pending HR Response\",\"interviewCount\":2}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(2));
        }
        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Pending Hiring Manager Response\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(2));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(2))
                .andExpect(jsonPath("$.totalApplications").value(1));

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"interviewCount\":-1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"interviewCount\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interviewCount").value(0));
    }

    @Test
        void archiveApplication_shouldHideFromActiveList() throws Exception {
                ApplicationRequest create = buildRequest("Archive Me");
        MvcResult createResult = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/applications/{id}/archive", id)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/applications")
                        .param("archived", "true")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void patchApplication_shouldRestoreArchivedApplicationAndKeepItsStatus() throws Exception {
        String id = createApplication("Restore Me");

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Rejected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"))
                .andExpect(jsonPath("$.archived").value(false));

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archivedAt").exists())
                .andExpect(jsonPath("$.status").value("Rejected"));

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.status").value("Rejected"));

        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.applications[0].status").value("Rejected"));

        mockMvc.perform(get("/api/v1/applications")
                        .param("archived", "true")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void patchApplication_archivingTwice_shouldKeepTheFirstArchivedAt() throws Exception {
        String id = createApplication("Idempotent Archive");

        MvcResult firstArchive = mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isOk())
                .andReturn();

        String archivedAt = objectMapper.readTree(firstArchive.getResponse().getContentAsString())
                .get("archivedAt").asText();

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archivedAt").value(archivedAt));
    }

    @Test
    void patchApplication_shouldLeaveOmittedFieldsUnchanged() throws Exception {
        String id = createApplication("Partial Update");

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Only the note changed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Only the note changed"))
                .andExpect(jsonPath("$.vacancyName").value("Partial Update"))
                .andExpect(jsonPath("$.recruiterName").value("Some Recruiter"))
                .andExpect(jsonPath("$.organization").value("HR Department"))
                .andExpect(jsonPath("$.status").value("RH"))
                .andExpect(jsonPath("$.applicationDate").value(LocalDate.now().minusDays(1).toString()));
    }

    @Test
    void patchApplication_shouldRejectInvalidStatus() throws Exception {
        String id = createApplication("Bad Status");

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Not A Real Status\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchApplication_explicitNullStatus_revertsToSendLater() throws Exception {
        String id = createApplication("Reverting To Send Later");

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.toSendLater").value(true))
                .andExpect(jsonPath("$.applicationDate").doesNotExist());
    }

    @Test
    void patchApplication_shouldReturn404_whenNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/applications/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchApplication_shouldReturn403_whenNotAuthenticated() throws Exception {
        String id = createApplication("Unauthenticated Patch");

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchApplication_shouldReturn404_whenApplicationBelongsToAnotherUser() throws Exception {
        String id = createApplication("Owned By First User");

        RegisterRequest otherUser = new RegisterRequest(
                "Other User", "otheruser@example.com", "pass1234", "pass1234", true);
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherUser)))
                .andReturn();
        String otherToken = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(), AuthResponse.class).accessToken();

        mockMvc.perform(patch("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void getAll_shouldReturnPagedResponse() throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRequest("App 1"))));
        mockMvc.perform(post("/api/v1/applications")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRequest("App 2"))));

        mockMvc.perform(get("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications").isArray())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void updateReminder_shouldReturn200() throws Exception {
        ApplicationRequest create = buildRequest("Reminder Test");
        MvcResult createResult = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/applications/{id}/reminder", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recruiterDmReminderEnabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recruiterDmReminderEnabled").value(true));
    }

    @Test
    void applicationLifecycle_shouldAutoAwardXpAndAvoidDuplicates() throws Exception {
        ApplicationRequest createRequest = new ApplicationRequest(
                "Gamified App", "Recruiter", "Org",
                "https://example.com/job", LocalDate.now(),
                false, false, null, "RH", false, null, null, null
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        assertProfileXp(10, 1);

        mockMvc.perform(patch("/api/v1/applications/{id}/mark-dm-sent", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertProfileXp(25, 1);

        mockMvc.perform(patch("/api/v1/applications/{id}/mark-dm-sent", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertProfileXp(25, 1);

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Technical Test\"}"))
                .andExpect(status().isOk());
        assertProfileXp(75, 1);

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Technical Test\"}"))
                .andExpect(status().isOk());
        assertProfileXp(75, 1);

        ApplicationRequest addNoteRequest = new ApplicationRequest(
                "Gamified App", "Recruiter", "Org",
                "https://example.com/job", LocalDate.now(),
                false, false, null, "Technical Test", false, "First note", null, null
        );

        mockMvc.perform(put("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addNoteRequest)))
                .andExpect(status().isOk());
        assertProfileXp(80, 1);

        ApplicationRequest updateNoteRequest = new ApplicationRequest(
                "Gamified App", "Recruiter", "Org",
                "https://example.com/job", LocalDate.now(),
                false, false, null, "Technical Test", false, "Edited note", null, null
        );

        mockMvc.perform(put("/api/v1/applications/{id}", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateNoteRequest)))
                .andExpect(status().isOk());
        assertProfileXp(80, 1);

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Offer Negotiation\"}"))
                .andExpect(status().isOk());
        assertProfileXp(580, 3);
    }

    private void assertProfileXp(int expectedXp, int expectedLevel) throws Exception {
        mockMvc.perform(get("/api/v1/gamification/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentXp").value(expectedXp))
                .andExpect(jsonPath("$.level").value(expectedLevel));
    }

    private String createApplication(String vacancyName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(vacancyName))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private ApplicationRequest buildRequest(String vacancyName) {
        return new ApplicationRequest(
                vacancyName, "Some Recruiter", "HR Department",
                "https://example.com/job", LocalDate.now().minusDays(1),
                false, false, null, "RH", false, "Remember to follow up", null, null
        );
    }
}
