package com.jobtracker.repository.integrations;

import com.jobtracker.entity.integrations.TinyFetchSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TinyFetchSettingsRepository extends JpaRepository<TinyFetchSettings, UUID> {
    Optional<TinyFetchSettings> findByUserId(UUID userId);
}
