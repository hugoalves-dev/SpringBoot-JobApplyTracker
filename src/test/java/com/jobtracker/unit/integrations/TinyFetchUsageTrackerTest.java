package com.jobtracker.unit.integrations;

import com.jobtracker.entity.integrations.TinyFetchDailyUsage;
import com.jobtracker.exception.IntegrationRateLimitException;
import com.jobtracker.repository.integrations.TinyFetchDailyUsageRepository;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TinyFetchUsageTrackerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T10:00:00Z"));
    private final Map<String, TinyFetchDailyUsage> rows = new HashMap<>();
    private TinyFetchUsageTracker tracker;

    @BeforeEach
    void setUp() {
        TinyFetchDailyUsageRepository repository = mock(TinyFetchDailyUsageRepository.class);
        when(repository.findByUserIdAndUsageDate(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any())).thenAnswer(inv -> {
            TinyFetchDailyUsage usage = inv.getArgument(0);
            rows.put(usage.getUserId() + "|" + usage.getUsageDate(), usage);
            return usage;
        });
        tracker = new TinyFetchUsageTracker(repository, clock);
    }

    @Test
    void enforcesPerMinuteWindowAndRecoversAfterItSlides() {
        tracker.reserve(USER, 2, 100);
        tracker.reserve(USER, 2, 100);

        assertThatThrownBy(() -> tracker.reserve(USER, 2, 100))
                .isInstanceOfSatisfying(IntegrationRateLimitException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("TINYFETCH_MINUTE_LIMIT");
                    assertThat(ex.getRetryAfter()).isPositive();
                });

        clock.advance(Duration.ofSeconds(61));
        tracker.reserve(USER, 2, 100);
    }

    @Test
    void enforcesDailyLimitPerUserAndResetsNextUtcDay() {
        tracker.reserve(USER, 100, 1);
        assertThatThrownBy(() -> tracker.reserve(USER, 100, 1))
                .isInstanceOfSatisfying(IntegrationRateLimitException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TINYFETCH_DAILY_LIMIT"));

        tracker.reserve(OTHER, 100, 1);
        assertThat(tracker.usedToday(USER)).isEqualTo(1);

        clock.advance(Duration.ofDays(1));
        tracker.reserve(USER, 100, 1);
        assertThat(rows).containsKey(USER + "|" + LocalDate.of(2026, 11, 25));
    }

    @Test
    void recordsFailuresWithoutConsumingExtraQuota() {
        tracker.reserve(USER, 10, 10);
        tracker.recordFailure(USER);

        TinyFetchDailyUsage usage = rows.get(USER + "|" + LocalDate.of(2026, 11, 24));
        assertThat(usage.getFetchCount()).isEqualTo(1);
        assertThat(usage.getFailureCount()).isEqualTo(1);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
