package com.jobtracker.repository.integrations;

import com.jobtracker.entity.integrations.GmailOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface GmailOAuthStateRepository extends JpaRepository<GmailOAuthState, UUID> {
    Optional<GmailOAuthState> findByStateToken(String stateToken);

    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
