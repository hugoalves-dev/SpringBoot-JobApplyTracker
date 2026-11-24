package com.jobtracker.repository.integrations;

import com.jobtracker.entity.integrations.TinyFetchDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface TinyFetchDailyUsageRepository extends JpaRepository<TinyFetchDailyUsage, UUID> {
    Optional<TinyFetchDailyUsage> findByUserIdAndUsageDate(UUID userId, LocalDate usageDate);
}
