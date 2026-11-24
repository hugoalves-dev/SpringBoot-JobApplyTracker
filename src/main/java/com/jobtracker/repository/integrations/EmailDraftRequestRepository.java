package com.jobtracker.repository.integrations;

import com.jobtracker.entity.integrations.EmailDraftRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailDraftRequestRepository extends JpaRepository<EmailDraftRequest, UUID> {
    Optional<EmailDraftRequest> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
