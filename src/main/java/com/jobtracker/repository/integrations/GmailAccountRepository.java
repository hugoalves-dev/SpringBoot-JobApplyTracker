package com.jobtracker.repository.integrations;

import com.jobtracker.entity.integrations.GmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GmailAccountRepository extends JpaRepository<GmailAccount, UUID> {
    List<GmailAccount> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<GmailAccount> findByIdAndUserId(UUID id, UUID userId);

    Optional<GmailAccount> findByUserIdAndEmailIgnoreCase(UUID userId, String email);

    Optional<GmailAccount> findByUserIdAndProfileNameIgnoreCase(UUID userId, String profileName);

    long countByUserId(UUID userId);
}
