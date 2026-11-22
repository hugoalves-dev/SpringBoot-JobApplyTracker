package com.jobtracker.repository.assistant;

import com.jobtracker.entity.AssistantQuotaLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssistantQuotaLockRepository extends JpaRepository<AssistantQuotaLock, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AssistantQuotaLock l where l.name = :name")
    Optional<AssistantQuotaLock> lockByName(@Param("name") String name);
}
