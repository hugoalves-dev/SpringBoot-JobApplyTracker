package com.jobtracker.tasks;

import com.jobtracker.service.assistant.AssistantQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AssistantReservationCleaner {
    private static final Logger log = LoggerFactory.getLogger(AssistantReservationCleaner.class);

    private final AssistantQuotaService quota;

    public AssistantReservationCleaner(AssistantQuotaService quota) {
        this.quota = quota;
    }

    @Scheduled(cron = "0 17 3 * * ?")
    public void purgeExpiredReservations() {
        int deleted = quota.purgeExpired();
        if (deleted > 0) log.info("event=ASSISTANT_RESERVATIONS_PURGED count={}", deleted);
    }
}
