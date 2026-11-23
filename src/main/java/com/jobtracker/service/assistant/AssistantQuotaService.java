package com.jobtracker.service.assistant;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.dto.assistant.AssistantUsageResponse;
import com.jobtracker.entity.AssistantQuotaLock;
import com.jobtracker.entity.AssistantRequestReservation;
import com.jobtracker.entity.enums.AssistantReservationStatus;
import com.jobtracker.exception.AssistantQuotaExceededException;
import com.jobtracker.exception.AssistantQuotaExceededException.Reason;
import com.jobtracker.exception.ServiceUnavailableException;
import com.jobtracker.repository.assistant.AssistantQuotaLockRepository;
import com.jobtracker.repository.assistant.AssistantRequestReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jobtracker.entity.enums.AssistantReservationStatus.CHARGED;
import static com.jobtracker.entity.enums.AssistantReservationStatus.IN_PROGRESS;
import static com.jobtracker.entity.enums.AssistantReservationStatus.PROVIDER_REACHED;

/**
 * Database-backed quota for Ask ApplyWell. Every check-and-reserve runs while holding a row lock
 * (SELECT ... FOR UPDATE) so concurrent requests, tabs and application instances cannot overshoot.
 */
@Service
public class AssistantQuotaService {
    private static final Logger log = LoggerFactory.getLogger(AssistantQuotaService.class);
    private static final Duration MIN_RETENTION = Duration.ofDays(2);

    private final AssistantRequestReservationRepository reservations;
    private final AssistantQuotaLockRepository locks;
    private final AssistantProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate reserveTx;

    @Autowired
    public AssistantQuotaService(AssistantRequestReservationRepository reservations,
                                 AssistantQuotaLockRepository locks,
                                 AssistantProperties properties,
                                 MeterRegistry meters,
                                 PlatformTransactionManager transactionManager) {
        this(reservations, locks, properties, meters, transactionManager, Clock.systemUTC());
    }

    public AssistantQuotaService(AssistantRequestReservationRepository reservations,
                                 AssistantQuotaLockRepository locks,
                                 AssistantProperties properties,
                                 MeterRegistry meters,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.reservations = reservations;
        this.locks = locks;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        // READ_COMMITTED: counts taken after acquiring the lock must see rows committed by the previous holder.
        this.reserveTx = new TransactionTemplate(transactionManager);
        this.reserveTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Reservation reserve(UUID userId) {
        if (!properties.getQuota().isEnabled()) return Reservation.untracked();
        return reserveTx.execute(status -> reserveLocked(userId));
    }

    private Reservation reserveLocked(UUID userId) {
        AssistantProperties.Quota quota = properties.getQuota();

        locks.lockByName(AssistantQuotaLock.GLOBAL)
                .orElseThrow(() -> new ServiceUnavailableException("Assistant quota storage is not initialized"));

        Instant now = clock.instant();
        ZonedDateTime dayStart = now.atZone(quota.getResetZone()).toLocalDate().atStartOfDay(quota.getResetZone());
        Instant nextReset = dayStart.plusDays(1).toInstant();
        LocalDateTime dayStartUtc = utc(dayStart.toInstant());

        if (quota.getDailyLimit() > 0
                && reservations.countForUser(userId, dayStartUtc, CHARGED) >= quota.getDailyLimit()) {
            throw reject(userId, Reason.DAILY_QUOTA, now, nextReset);
        }

        if (quota.getMaxConcurrent() > 0) {
            LocalDateTime leaseStart = utc(now.minus(inFlightLease()));
            if (reservations.countForUser(userId, leaseStart, Set.of(IN_PROGRESS)) >= quota.getMaxConcurrent()) {
                throw reject(userId, Reason.CONCURRENT_REQUEST, now, null);
            }
        }

        if (quota.getRateLimit() > 0) {
            LocalDateTime windowStart = windowStart(now, quota.getRateWindow());
            if (reservations.countForUser(userId, windowStart, PROVIDER_REACHED) >= quota.getRateLimit()) {
                Instant retryAt = reservations.oldestForUser(userId, windowStart, PROVIDER_REACHED)
                        .map(oldest -> oldest.toInstant(ZoneOffset.UTC).plus(quota.getRateWindow()))
                        .orElse(now.plus(quota.getRateWindow()));
                throw reject(userId, Reason.RATE_LIMITED, now, retryAt);
            }
        }

        if (quota.getGlobalDailyLimit() > 0
                && reservations.countGlobal(dayStartUtc, PROVIDER_REACHED) >= quota.getGlobalDailyLimit()) {
            throw reject(userId, Reason.GLOBAL_LIMIT, now, nextReset);
        }

        if (quota.getGlobalRateLimit() > 0) {
            LocalDateTime windowStart = windowStart(now, quota.getGlobalRateWindow());
            if (reservations.countGlobal(windowStart, PROVIDER_REACHED) >= quota.getGlobalRateLimit()) {
                Instant retryAt = reservations.oldestGlobal(windowStart, PROVIDER_REACHED)
                        .map(oldest -> oldest.toInstant(ZoneOffset.UTC).plus(quota.getGlobalRateWindow()))
                        .orElse(now.plus(quota.getGlobalRateWindow()));
                throw reject(userId, Reason.GLOBAL_LIMIT, now, retryAt);
            }
        }

        AssistantRequestReservation saved = reservations.saveAndFlush(new AssistantRequestReservation(userId, utc(now)));
        meters.counter("assistant.quota.decisions", "result", "allowed").increment();
        return new Reservation(this, saved.getId());
    }

    public AssistantUsageResponse usage(UUID userId) {
        AssistantProperties.Quota quota = properties.getQuota();
        Instant now = clock.instant();
        ZonedDateTime dayStart = now.atZone(quota.getResetZone()).toLocalDate().atStartOfDay(quota.getResetZone());
        long used = reservations.countForUser(userId, utc(dayStart.toInstant()), CHARGED);
        boolean dailyEnabled = quota.isEnabled() && quota.getDailyLimit() > 0;
        Integer limit = dailyEnabled ? quota.getDailyLimit() : null;
        Integer remaining = dailyEnabled ? (int) Math.max(0, quota.getDailyLimit() - used) : null;
        return new AssistantUsageResponse(quota.isEnabled(), limit, used, remaining,
                dayStart.plusDays(1).toInstant(), quota.getResetZone().getId());
    }

    public int purgeExpired() {
        Duration retention = properties.getQuota().getRetention();
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) retention = MIN_RETENTION;
        return reservations.deleteCreatedBefore(utc(clock.instant().minus(retention)));
    }

    void finish(UUID id, AssistantReservationStatus status, Integer promptTokens, Integer completionTokens) {
        reservations.finish(id, status, utc(clock.instant()), promptTokens, completionTokens);
        meters.counter("assistant.quota.reservations.finished", "status", status.name()).increment();
    }

    private Duration inFlightLease() {
        return properties.getStreamTimeout().plus(properties.getQuota().getInFlightLeaseMargin());
    }

    private AssistantQuotaExceededException reject(UUID userId, Reason reason, Instant now, Instant retryAt) {
        Long retryAfter = retryAt == null ? null : Math.max(1, Duration.between(now, retryAt).toSeconds());
        meters.counter("assistant.quota.decisions", "result", reason.name().toLowerCase()).increment();
        log.info("event=ASSISTANT_QUOTA_REJECTED reason={} userId={} retryAfterSeconds={}", reason, userId, retryAfter);
        return new AssistantQuotaExceededException(reason, retryAfter, retryAt);
    }

    /** Exclusive lower bound (column precision is microseconds), so a retry exactly at Retry-After succeeds. */
    private static LocalDateTime windowStart(Instant now, Duration window) {
        return utc(now.minus(window).plus(1, ChronoUnit.MICROS));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * One authorized slot. Exactly one terminal transition wins; later calls are no-ops, and
     * an unfinished slot (crash, lost callback) expires from the in-flight check after the lease
     * while staying charged, so restarts never hand out free messages.
     */
    public static final class Reservation {
        private final AssistantQuotaService owner;
        private final UUID id;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Reservation(AssistantQuotaService owner, UUID id) {
            this.owner = owner;
            this.id = id;
        }

        /** A slot that is not persisted (quota disabled); every transition is a no-op. */
        public static Reservation untracked() {
            return new Reservation(null, null);
        }

        public UUID id() { return id; }

        public void complete(Integer promptTokens, Integer completionTokens) {
            finish(AssistantReservationStatus.COMPLETED, promptTokens, completionTokens);
        }

        /** Provider error: refunded only if nothing was streamed to the user yet. */
        public void fail(boolean outputEmitted, Integer promptTokens, Integer completionTokens) {
            finish(outputEmitted ? AssistantReservationStatus.FAILED : AssistantReservationStatus.REFUNDED,
                    promptTokens, completionTokens);
        }

        /** Client disconnect or timeout after the provider call started: still charged. */
        public void cancel(Integer promptTokens, Integer completionTokens) {
            finish(AssistantReservationStatus.CANCELLED, promptTokens, completionTokens);
        }

        /** Local failure before the provider was called: not charged anywhere. */
        public void release() {
            finish(AssistantReservationStatus.RELEASED, null, null);
        }

        private void finish(AssistantReservationStatus status, Integer promptTokens, Integer completionTokens) {
            if (id == null || !finished.compareAndSet(false, true)) return;
            try {
                owner.finish(id, status, promptTokens, completionTokens);
            } catch (RuntimeException ex) {
                log.warn("event=ASSISTANT_RESERVATION_FINISH_FAILED reservationId={} status={} message={}",
                        id, status, ex.getMessage());
            }
        }
    }
}
