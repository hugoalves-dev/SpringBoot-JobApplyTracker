package com.jobtracker.service;

import com.jobtracker.entity.InterviewEvent;
import com.jobtracker.entity.JobApplication;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.InterviewEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class InterviewMetricsService {

    // Includes both legacy enum constant names (stored in DB for old records) and new English values.
    private static final Set<String> INTERVIEW_STATUSES = Set.of(
            "ENTREVISTA_MARCADA",
            "FIZ_A_RH_AGUARDANDO_ATUALIZACAO",
            "FIZ_A_HIRING_MANAGER_AGUARDANDO_ATUALIZACAO",
            "TESTE_TECNICO",
            "FIZ_TESTE_TECNICO_AGUARDANDO_ATUALIZACAO",
            "RH_NEGOCIACAO",
            "Pending HR Response",
            "Pending Hiring Manager Response",
            "Technical Test",
            "Pending Technical Test Response",
            "Offer Negotiation"
    );

    private final ApplicationRepository applicationRepository;
    private final InterviewEventRepository eventRepository;

    public InterviewMetricsService(ApplicationRepository applicationRepository,
                                   InterviewEventRepository eventRepository) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
    }

    public boolean isInterviewStatus(String status) {
        return status != null && INTERVIEW_STATUSES.contains(status);
    }

    public boolean wasInterviewTriggered(String oldStatus, String newStatus) {
        return isInterviewStatus(newStatus) && !isInterviewStatus(oldStatus);
    }

    @Transactional
    public void recordStatusTransition(JobApplication application, String oldStatus, String newStatus) {
        if (!wasInterviewTriggered(oldStatus, newStatus)) {
            return;
        }

        // Status is funnel history, not evidence that an interview was completed.
        // The confirmed absolute count is supplied explicitly by the caller.

        InterviewEvent event = new InterviewEvent();
        event.setUser(application.getUser());
        event.setApplication(application);
        event.setOldStatus(oldStatus);
        event.setNewStatus(newStatus);
        event.setOccurredAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public long getInterviewCount(UUID userId) {
        return applicationRepository.sumInterviewCountByUserId(userId);
    }
}
