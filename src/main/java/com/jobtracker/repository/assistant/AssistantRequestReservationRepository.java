package com.jobtracker.repository.assistant;

import com.jobtracker.entity.AssistantRequestReservation;
import com.jobtracker.entity.enums.AssistantReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AssistantRequestReservationRepository extends JpaRepository<AssistantRequestReservation, UUID> {

    @Query("""
            select count(r) from AssistantRequestReservation r
            where r.userId = :userId and r.createdAt >= :since and r.status in :statuses
            """)
    long countForUser(@Param("userId") UUID userId, @Param("since") LocalDateTime since,
                      @Param("statuses") Collection<AssistantReservationStatus> statuses);

    @Query("""
            select count(r) from AssistantRequestReservation r
            where r.createdAt >= :since and r.status in :statuses
            """)
    long countGlobal(@Param("since") LocalDateTime since,
                     @Param("statuses") Collection<AssistantReservationStatus> statuses);

    @Query("""
            select min(r.createdAt) from AssistantRequestReservation r
            where r.userId = :userId and r.createdAt >= :since and r.status in :statuses
            """)
    Optional<LocalDateTime> oldestForUser(@Param("userId") UUID userId, @Param("since") LocalDateTime since,
                                          @Param("statuses") Collection<AssistantReservationStatus> statuses);

    @Query("""
            select min(r.createdAt) from AssistantRequestReservation r
            where r.createdAt >= :since and r.status in :statuses
            """)
    Optional<LocalDateTime> oldestGlobal(@Param("since") LocalDateTime since,
                                         @Param("statuses") Collection<AssistantReservationStatus> statuses);

    /** Only an IN_PROGRESS reservation can transition, so finishing is idempotent and race-free. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AssistantRequestReservation r
            set r.status = :status, r.finishedAt = :finishedAt,
                r.promptTokens = :promptTokens, r.completionTokens = :completionTokens
            where r.id = :id and r.status = com.jobtracker.entity.enums.AssistantReservationStatus.IN_PROGRESS
            """)
    int finish(@Param("id") UUID id, @Param("status") AssistantReservationStatus status,
               @Param("finishedAt") LocalDateTime finishedAt,
               @Param("promptTokens") Integer promptTokens,
               @Param("completionTokens") Integer completionTokens);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from AssistantRequestReservation r where r.createdAt < :before")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);
}
