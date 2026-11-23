package com.jobtracker.integration;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.dto.assistant.AssistantUsageResponse;
import com.jobtracker.entity.enums.AssistantReservationStatus;
import com.jobtracker.exception.AssistantQuotaExceededException;
import com.jobtracker.exception.AssistantQuotaExceededException.Reason;
import com.jobtracker.repository.assistant.AssistantQuotaLockRepository;
import com.jobtracker.repository.assistant.AssistantRequestReservationRepository;
import com.jobtracker.service.assistant.AssistantQuotaService;
import com.jobtracker.service.assistant.AssistantQuotaService.Reservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AssistantQuotaServiceIT {

    @Autowired
    private AssistantRequestReservationRepository reservations;

    @Autowired
    private AssistantQuotaLockRepository locks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AssistantProperties properties;
    private MutableClock clock;
    private AssistantQuotaService quota;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        properties = new AssistantProperties();
        AssistantProperties.Quota limits = properties.getQuota();
        limits.setDailyLimit(0);
        limits.setRateLimit(0);
        limits.setMaxConcurrent(0);
        limits.setGlobalDailyLimit(0);
        limits.setGlobalRateLimit(0);
        limits.setResetZone(ZoneId.of("America/Sao_Paulo"));
        // 2026-11-22T12:00 in São Paulo (UTC-3).
        clock = new MutableClock(Instant.parse("2026-11-22T15:00:00Z"));
        quota = new AssistantQuotaService(reservations, locks, properties, new SimpleMeterRegistry(),
                transactionManager, clock);
    }

    @Test
    void dailyLimitBlocksUntilLocalMidnightAndIsIsolatedPerUser() {
        properties.getQuota().setDailyLimit(2);
        UUID user = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        quota.reserve(user).complete(10, 5);
        quota.reserve(user).complete(10, 5);

        assertThatThrownBy(() -> quota.reserve(user))
                .isInstanceOfSatisfying(AssistantQuotaExceededException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(Reason.DAILY_QUOTA);
                    assertThat(ex.getCode()).isEqualTo("ASSISTANT_DAILY_QUOTA_EXCEEDED");
                    assertThat(ex.getRetryAt()).isEqualTo(Instant.parse("2026-11-23T03:00:00Z"));
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(Duration.ofHours(12).toSeconds());
                });
        assertThat(quota.reserve(otherUser).id()).isNotNull();

        clock.set(Instant.parse("2026-11-23T02:59:59Z"));
        assertThatThrownBy(() -> quota.reserve(user)).isInstanceOf(AssistantQuotaExceededException.class);

        clock.set(Instant.parse("2026-11-23T03:00:00Z"));
        assertThat(quota.reserve(user).id()).isNotNull();
    }

    @Test
    void blockedRequestsDoNotConsumeQuota() {
        properties.getQuota().setDailyLimit(1);
        UUID user = UUID.randomUUID();
        quota.reserve(user).complete(null, null);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> quota.reserve(user)).isInstanceOf(AssistantQuotaExceededException.class);
        }

        assertThat(reservations.count()).isEqualTo(1);
        assertThat(quota.usage(user).used()).isEqualTo(1);
    }

    @Test
    void rateLimitUsesSlidingWindowAndReportsRetryAfter() {
        properties.getQuota().setRateLimit(2);
        properties.getQuota().setRateWindow(Duration.ofMinutes(1));
        UUID user = UUID.randomUUID();

        quota.reserve(user).complete(null, null);
        clock.advance(Duration.ofSeconds(20));
        quota.reserve(user).complete(null, null);

        assertThatThrownBy(() -> quota.reserve(user))
                .isInstanceOfSatisfying(AssistantQuotaExceededException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(Reason.RATE_LIMITED);
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(40);
                });

        clock.advance(Duration.ofSeconds(40));
        assertThat(quota.reserve(user).id()).isNotNull();
    }

    @Test
    void onlyOneGenerationInFlightPerUserUntilFinishedOrLeaseExpires() {
        properties.getQuota().setMaxConcurrent(1);
        properties.setStreamTimeout(Duration.ofSeconds(60));
        properties.getQuota().setInFlightLeaseMargin(Duration.ofSeconds(30));
        UUID user = UUID.randomUUID();

        Reservation running = quota.reserve(user);
        assertThatThrownBy(() -> quota.reserve(user))
                .isInstanceOfSatisfying(AssistantQuotaExceededException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(Reason.CONCURRENT_REQUEST));
        assertThat(quota.reserve(UUID.randomUUID()).id()).isNotNull();

        running.complete(null, null);
        Reservation next = quota.reserve(user);
        assertThat(next.id()).isNotNull();

        // Simulates a crashed instance that never finished the slot: it stops blocking after the lease.
        clock.advance(Duration.ofSeconds(91));
        assertThat(quota.reserve(user).id()).isNotNull();
        assertThat(quota.usage(user).used()).isEqualTo(3);
    }

    @Test
    void globalLimitsProtectTheSharedProviderAcrossUsers() {
        properties.getQuota().setGlobalRateLimit(3);
        properties.getQuota().setGlobalRateWindow(Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) quota.reserve(UUID.randomUUID()).complete(null, null);

        assertThatThrownBy(() -> quota.reserve(UUID.randomUUID()))
                .isInstanceOfSatisfying(AssistantQuotaExceededException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(Reason.GLOBAL_LIMIT);
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(60);
                });

        properties.getQuota().setGlobalRateLimit(0);
        properties.getQuota().setGlobalDailyLimit(4);
        clock.advance(Duration.ofMinutes(5));
        quota.reserve(UUID.randomUUID()).complete(null, null);
        assertThatThrownBy(() -> quota.reserve(UUID.randomUUID()))
                .isInstanceOfSatisfying(AssistantQuotaExceededException.class,
                        ex -> assertThat(ex.getRetryAt()).isEqualTo(Instant.parse("2026-11-23T03:00:00Z")));
    }

    @Test
    void refundedAndReleasedSlotsFollowTheAccountingPolicy() {
        properties.getQuota().setDailyLimit(1);
        properties.getQuota().setRateLimit(2);
        UUID user = UUID.randomUUID();

        quota.reserve(user).release();
        quota.reserve(user).fail(false, null, null);
        assertThat(quota.usage(user).used()).isZero();

        // The refunded attempt reached the provider, so it still counts toward the short window.
        quota.reserve(user).complete(null, null);
        assertThatThrownBy(() -> quota.reserve(user))
                .isInstanceOfSatisfying(AssistantQuotaExceededException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(Reason.DAILY_QUOTA));

        assertThat(reservations.findAll()).extracting(r -> r.getStatus())
                .containsExactlyInAnyOrder(AssistantReservationStatus.RELEASED,
                        AssistantReservationStatus.REFUNDED, AssistantReservationStatus.COMPLETED);
    }

    @Test
    void failuresAfterOutputAndCancellationsAreCharged() {
        properties.getQuota().setDailyLimit(10);
        UUID user = UUID.randomUUID();

        quota.reserve(user).fail(true, 5, 3);
        quota.reserve(user).cancel(null, null);

        assertThat(quota.usage(user).used()).isEqualTo(2);
    }

    @Test
    void firstTerminalTransitionWins() {
        UUID user = UUID.randomUUID();
        Reservation reservation = quota.reserve(user);

        reservation.complete(12, 7);
        reservation.release();
        reservation.cancel(null, null);

        var stored = reservations.findById(reservation.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AssistantReservationStatus.COMPLETED);
        assertThat(stored.getPromptTokens()).isEqualTo(12);
        assertThat(stored.getCompletionTokens()).isEqualTo(7);
    }

    @Test
    void parallelRequestsCannotOvershootTheDailyLimit() throws Exception {
        properties.getQuota().setDailyLimit(5);
        UUID user = UUID.randomUUID();
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> {
                start.await();
                try {
                    quota.reserve(user);
                    return true;
                } catch (AssistantQuotaExceededException ex) {
                    return false;
                }
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) futures.add(executor.submit(task));
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> future : futures) if (future.get()) allowed++;
            assertThat(allowed).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
        assertThat(reservations.count()).isEqualTo(5);
    }

    @Test
    void usageReportsLimitRemainingAndNextReset() {
        properties.getQuota().setDailyLimit(30);
        UUID user = UUID.randomUUID();
        quota.reserve(user).complete(null, null);
        quota.reserve(UUID.randomUUID()).complete(null, null);

        AssistantUsageResponse usage = quota.usage(user);

        assertThat(usage.quotaEnabled()).isTrue();
        assertThat(usage.dailyLimit()).isEqualTo(30);
        assertThat(usage.used()).isEqualTo(1);
        assertThat(usage.remaining()).isEqualTo(29);
        assertThat(usage.resetAt()).isEqualTo(Instant.parse("2026-11-23T03:00:00Z"));
        assertThat(usage.resetTimezone()).isEqualTo("America/Sao_Paulo");
    }

    @Test
    void disabledQuotaDoesNotPersistOrBlock() {
        properties.getQuota().setEnabled(false);
        properties.getQuota().setDailyLimit(1);
        UUID user = UUID.randomUUID();

        quota.reserve(user).complete(null, null);
        quota.reserve(user).complete(null, null);

        assertThat(reservations.count()).isZero();
        assertThat(quota.usage(user).dailyLimit()).isNull();
    }

    @Test
    void purgeKeepsAtLeastTwoDaysOfHistory() {
        properties.getQuota().setRetention(Duration.ofHours(1));
        UUID user = UUID.randomUUID();
        quota.reserve(user).complete(null, null);

        clock.advance(Duration.ofDays(1));
        assertThat(quota.purgeExpired()).isZero();

        clock.advance(Duration.ofDays(2));
        assertThat(quota.purgeExpired()).isEqualTo(1);
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) { this.now = now; }

        void set(Instant value) { now = value; }
        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
