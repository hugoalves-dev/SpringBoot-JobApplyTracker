package com.jobtracker.service.integrations.tinyfetch;

import com.jobtracker.entity.integrations.TinyFetchDailyUsage;
import com.jobtracker.exception.IntegrationRateLimitException;
import com.jobtracker.repository.integrations.TinyFetchDailyUsageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the user's TinyFish quotas before each billable provider call: a sliding one-minute
 * window kept in memory and a per-UTC-day counter persisted in {@code tinyfetch_daily_usage}.
 * Reservations commit in their own transaction so the HTTP call is never made inside one.
 */
@Component
public class TinyFetchUsageTracker {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final TinyFetchDailyUsageRepository usageRepository;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Deque<Instant>> minuteWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public TinyFetchUsageTracker(TinyFetchDailyUsageRepository usageRepository) {
        this(usageRepository, Clock.systemUTC());
    }

    public TinyFetchUsageTracker(TinyFetchDailyUsageRepository usageRepository, Clock clock) {
        this.usageRepository = usageRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(UUID userId, int perMinuteLimit, int dailyLimit) {
        synchronized (locks.computeIfAbsent(userId, id -> new Object())) {
            Instant now = clock.instant();
            Deque<Instant> window = minuteWindows.computeIfAbsent(userId, id -> new ArrayDeque<>());
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= perMinuteLimit) {
                Duration retryAfter = Duration.between(now, window.peekFirst().plus(WINDOW));
                throw new IntegrationRateLimitException("TINYFETCH_MINUTE_LIMIT",
                        "TinyFish Fetch per-minute limit reached (" + perMinuteLimit + "/min). Retry shortly.",
                        retryAfter.isNegative() ? Duration.ofSeconds(1) : retryAfter);
            }

            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            TinyFetchDailyUsage usage = usageRepository.findByUserIdAndUsageDate(userId, today)
                    .orElseGet(() -> new TinyFetchDailyUsage(userId, today));
            if (usage.getFetchCount() >= dailyLimit) {
                Duration untilMidnight = Duration.between(now, today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
                throw new IntegrationRateLimitException("TINYFETCH_DAILY_LIMIT",
                        "TinyFish Fetch daily limit reached (" + dailyLimit + "/day). Paste the content manually or retry tomorrow (UTC).",
                        untilMidnight);
            }
            usage.setFetchCount(usage.getFetchCount() + 1);
            usageRepository.save(usage);
            window.addLast(now);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId) {
        synchronized (locks.computeIfAbsent(userId, id -> new Object())) {
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            usageRepository.findByUserIdAndUsageDate(userId, today).ifPresent(usage -> {
                usage.setFailureCount(usage.getFailureCount() + 1);
                usageRepository.save(usage);
            });
        }
    }

    @Transactional(readOnly = true)
    public int usedToday(UUID userId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return usageRepository.findByUserIdAndUsageDate(userId, today)
                .map(TinyFetchDailyUsage::getFetchCount)
                .orElse(0);
    }
}
