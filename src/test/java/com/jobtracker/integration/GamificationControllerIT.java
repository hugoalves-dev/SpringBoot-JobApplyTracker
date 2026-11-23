package com.jobtracker.integration;

import tools.jackson.databind.ObjectMapper;
import com.jobtracker.dto.auth.AuthResponse;
import com.jobtracker.dto.auth.RegisterRequest;
import com.jobtracker.dto.gamification.GamificationEventRequest;
import com.jobtracker.entity.enums.GamificationEventType;
import com.jobtracker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GamificationControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private InterviewEventRepository interviewEventRepository;

    @Autowired
    private UserGamificationRepository userGamificationRepository;

    @Autowired
    private UserAchievementRepository userAchievementRepository;

    @Autowired
    private UserInterviewMetricsRepository userInterviewMetricsRepository;

    @Autowired
    private GoogleDriveConnectionRepository googleDriveConnectionRepository;

    @Autowired
    private GoogleDriveBaseResumeRepository googleDriveBaseResumeRepository;

    @Autowired
    private WebAuthnChallengeRepository webAuthnChallengeRepository;

    @Autowired
    private WebAuthnCredentialRepository webAuthnCredentialRepository;

    @BeforeEach
    void cleanDb() {
        userAchievementRepository.deleteAll();
        userGamificationRepository.deleteAll();
        interviewEventRepository.deleteAll();
        applicationRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userInterviewMetricsRepository.deleteAll();
        googleDriveBaseResumeRepository.deleteAll();
        googleDriveConnectionRepository.deleteAll();
        webAuthnChallengeRepository.deleteAll();
        webAuthnCredentialRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getProfile_shouldReturn403_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/gamification/profile"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getProfile_shouldCreateDefaultSnapshot_whenAuthenticated() throws Exception {
        String accessToken = registerAndGetAccessToken("profile-gamification@example.com");

        mockMvc.perform(get("/api/v1/gamification/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentXp").value(0))
                .andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.nextLevelXp").value(100))
                .andExpect(jsonPath("$.rankTitle").value("Desempregado de Aluguel"));
    }

    @Test
    void applyEvent_shouldReturnCreatedAndUpdatedProfile() throws Exception {
        String accessToken = registerAndGetAccessToken("event-gamification@example.com");
        GamificationEventRequest request = new GamificationEventRequest(
                GamificationEventType.RECRUITER_DM_SENT,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/gamification/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("RECRUITER_DM_SENT"))
                .andExpect(jsonPath("$.xpAwarded").value(15))
                .andExpect(jsonPath("$.profile.currentXp").value(15))
                .andExpect(jsonPath("$.profile.level").value(1));
    }

    @Test
    void getAchievements_shouldReturnCatalog() throws Exception {
        String accessToken = registerAndGetAccessToken("achievements-gamification@example.com");

        mockMvc.perform(get("/api/v1/gamification/achievements")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("EARLY_BIRD"))
                .andExpect(jsonPath("$[0].unlocked").value(false));
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        RegisterRequest request = new RegisterRequest("Gamification User", email, "pass1234", "pass1234", true);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        return response.accessToken();
    }
}
