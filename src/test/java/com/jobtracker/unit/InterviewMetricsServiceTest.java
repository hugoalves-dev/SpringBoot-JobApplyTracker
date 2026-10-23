package com.jobtracker.unit;

import com.jobtracker.entity.JobApplication;
import com.jobtracker.entity.User;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.InterviewEventRepository;
import com.jobtracker.service.InterviewMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InterviewMetricsServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private InterviewEventRepository eventRepository;

    private InterviewMetricsService service;
    private User user;
    private JobApplication application;

    @BeforeEach
    void setUp() {
        service = new InterviewMetricsService(applicationRepository, eventRepository);

        user = new User();
        user.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        application = new JobApplication();
        application.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        application.setUser(user);
    }

    @Test
    void isInterviewStatus_shouldDetectNewEnglishValues() {
        assertThat(service.isInterviewStatus("Pending HR Response")).isTrue();
        assertThat(service.isInterviewStatus("Pending Hiring Manager Response")).isTrue();
        assertThat(service.isInterviewStatus("Technical Test")).isTrue();
        assertThat(service.isInterviewStatus("Pending Technical Test Response")).isTrue();
        assertThat(service.isInterviewStatus("Offer Negotiation")).isTrue();
        assertThat(service.isInterviewStatus("RH")).isFalse();
        assertThat(service.isInterviewStatus("Rejected")).isFalse();
        assertThat(service.isInterviewStatus("Unknown")).isFalse();
    }

    @Test
    void isInterviewStatus_shouldDetectLegacyConstantNames() {
        assertThat(service.isInterviewStatus("ENTREVISTA_MARCADA")).isTrue();
        assertThat(service.isInterviewStatus("FIZ_A_RH_AGUARDANDO_ATUALIZACAO")).isTrue();
        assertThat(service.isInterviewStatus("TESTE_TECNICO")).isTrue();
    }

    @Test
    void wasInterviewTriggered_shouldOnlyDetectEntryIntoInterviewStatus() {
        assertThat(service.wasInterviewTriggered("RH", "Technical Test")).isTrue();
        assertThat(service.wasInterviewTriggered("Technical Test", "Technical Test")).isFalse();
        assertThat(service.wasInterviewTriggered("Technical Test", "Offer Negotiation")).isFalse();
        assertThat(service.wasInterviewTriggered("Technical Test", "Rejected")).isFalse();
        assertThat(service.wasInterviewTriggered("Rejected", "Offer Negotiation")).isTrue();
    }

    @Test
    void recordStatusTransition_shouldPreserveConfirmedCountAndLogStatusHistory() {
        application.setInterviewCount(2);
        service.recordStatusTransition(application, "RH", "Technical Test");

        assertThat(application.getInterviewCount()).isEqualTo(2);
        verify(eventRepository).save(any());
    }

    @Test
    void recordStatusTransition_shouldNotIncrementWhenStayingWithinInterviewStatuses() {
        service.recordStatusTransition(application, "Technical Test", "Offer Negotiation");

        assertThat(application.getInterviewCount()).isEqualTo(0);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void recordStatusTransition_shouldNotIncrementForNonInterviewTransitions() {
        service.recordStatusTransition(application, "Technical Test", "Technical Test");
        service.recordStatusTransition(application, "RH", "Rejected");

        assertThat(application.getInterviewCount()).isEqualTo(0);
        verify(eventRepository, never()).save(any());
    }
}
