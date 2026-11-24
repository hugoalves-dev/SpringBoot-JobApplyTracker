package com.jobtracker.service.integrations.tinyfetch;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.dto.integrations.FetchUrlResponse;
import com.jobtracker.dto.integrations.FetchUrlResponse.Status;
import com.jobtracker.dto.integrations.IntegrationTestResponse;
import com.jobtracker.dto.integrations.TinyFetchSettingsRequest;
import com.jobtracker.dto.integrations.TinyFetchSettingsResponse;
import com.jobtracker.entity.User;
import com.jobtracker.entity.integrations.TinyFetchSettings;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.IntegrationRateLimitException;
import com.jobtracker.exception.ServiceUnavailableException;
import com.jobtracker.repository.integrations.TinyFetchSettingsRepository;
import com.jobtracker.service.integrations.PublicUrlValidator;
import com.jobtracker.service.integrations.SecretCipher;
import com.jobtracker.util.SecurityUtils;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads public URLs through the user's own TinyFish account. The returned content is untrusted
 * data for the AI client to interpret; nothing here acts on what the page says.
 */
@Service
public class TinyFetchService {

    private static final Logger log = LoggerFactory.getLogger(TinyFetchService.class);
    /** Below this, a page almost certainly hit a login wall or rendered nothing useful. */
    private static final int MIN_USEFUL_CONTENT_CHARS = 200;
    private static final int TEST_MESSAGE_MAX = 255;

    private final TinyFetchSettingsRepository settingsRepository;
    private final TinyFetchUsageTracker usageTracker;
    private final TinyFetchClient client;
    private final SecretCipher cipher;
    private final PublicUrlValidator urlValidator;
    private final SecurityUtils securityUtils;
    private final IntegrationProperties.TinyFetch properties;
    private final MeterRegistry meters;
    private final Clock clock;

    private final Map<String, CachedResult> cache;
    private final ConcurrentHashMap<String, CompletableFuture<FetchUrlResponse>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public TinyFetchService(TinyFetchSettingsRepository settingsRepository,
                            TinyFetchUsageTracker usageTracker,
                            TinyFetchClient client,
                            SecretCipher cipher,
                            PublicUrlValidator urlValidator,
                            SecurityUtils securityUtils,
                            IntegrationProperties properties,
                            MeterRegistry meters) {
        this(settingsRepository, usageTracker, client, cipher, urlValidator, securityUtils, properties, meters,
                Clock.systemUTC());
    }

    public TinyFetchService(TinyFetchSettingsRepository settingsRepository,
                     TinyFetchUsageTracker usageTracker,
                     TinyFetchClient client,
                     SecretCipher cipher,
                     PublicUrlValidator urlValidator,
                     SecurityUtils securityUtils,
                     IntegrationProperties properties,
                     MeterRegistry meters,
                     Clock clock) {
        this.settingsRepository = settingsRepository;
        this.usageTracker = usageTracker;
        this.client = client;
        this.cipher = cipher;
        this.urlValidator = urlValidator;
        this.securityUtils = securityUtils;
        this.properties = properties.getTinyfetch();
        this.meters = meters;
        this.clock = clock;
        int maxEntries = Math.max(1, this.properties.getCacheMaxEntries());
        this.cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
                return size() > maxEntries;
            }
        });
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TinyFetchSettingsResponse getSettings() {
        UUID userId = securityUtils.getCurrentUserId();
        return toResponse(settingsRepository.findByUserId(userId).orElse(null), userId);
    }

    @Transactional
    public TinyFetchSettingsResponse updateSettings(TinyFetchSettingsRequest request) {
        User user = securityUtils.getCurrentUser();
        TinyFetchSettings settings = settingsRepository.findByUserId(user.getId()).orElse(null);
        boolean hasNewKey = request.apiKey() != null && !request.apiKey().isBlank();
        if (settings == null) {
            if (!hasNewKey) {
                throw new BadRequestException("apiKey is required to configure TinyFish Fetch");
            }
            settings = new TinyFetchSettings();
            settings.setUser(user);
            settings.setPerMinuteLimit(properties.getMaxPerMinuteLimit());
            settings.setDailyLimit(properties.getMaxDailyLimit());
        }
        if (hasNewKey) {
            String apiKey = request.apiKey().trim();
            settings.setApiKeyEncrypted(cipher.encrypt(apiKey));
            settings.setApiKeyHint(SecretCipher.mask(apiKey));
            settings.setLastTestedAt(null);
            settings.setLastTestSuccess(null);
            settings.setLastTestMessage(null);
        }
        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }
        if (request.perMinuteLimit() != null) {
            settings.setPerMinuteLimit(boundedLimit(request.perMinuteLimit(), properties.getMaxPerMinuteLimit(), "perMinuteLimit"));
        }
        if (request.dailyLimit() != null) {
            settings.setDailyLimit(boundedLimit(request.dailyLimit(), properties.getMaxDailyLimit(), "dailyLimit"));
        }
        settings = settingsRepository.save(settings);
        evictUserCache(user.getId());
        log.info("event=TINYFETCH_SETTINGS_UPDATED userId={} enabled={} keyChanged={}",
                user.getId(), settings.isEnabled(), hasNewKey);
        return toResponse(settings, user.getId());
    }

    @Transactional
    public void deleteSettings() {
        UUID userId = securityUtils.getCurrentUserId();
        settingsRepository.findByUserId(userId).ifPresent(settingsRepository::delete);
        evictUserCache(userId);
        log.info("event=TINYFETCH_SETTINGS_DELETED userId={}", userId);
    }

    public IntegrationTestResponse testConnection() {
        UUID userId = securityUtils.getCurrentUserId();
        TinyFetchSettings settings = settingsRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("TinyFish Fetch is not configured"));
        long started = System.nanoTime();
        boolean success;
        String message;
        try {
            usageTracker.reserve(userId, settings.getPerMinuteLimit(), settings.getDailyLimit());
            TinyFetchClient.FetchOutcome outcome = client.fetch(cipher.decrypt(settings.getApiKeyEncrypted()), properties.getTestUrl());
            success = outcome.content() != null;
            message = success ? "TinyFish Fetch is working" : "TinyFish answered but returned no content: " + outcome.errorCode();
        } catch (TinyFetchException ex) {
            success = false;
            message = ex.getMessage();
        } catch (IntegrationRateLimitException ex) {
            success = false;
            message = ex.getMessage();
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        settings.setLastTestedAt(LocalDateTime.now(clock));
        settings.setLastTestSuccess(success);
        settings.setLastTestMessage(message.length() > TEST_MESSAGE_MAX ? message.substring(0, TEST_MESSAGE_MAX) : message);
        settingsRepository.save(settings);
        return new IntegrationTestResponse(success, message, latencyMs);
    }

    // ── Fetch ────────────────────────────────────────────────────────────────

    public FetchUrlResponse fetch(String rawUrl, boolean bypassCache) {
        UUID userId = securityUtils.getCurrentUserId();
        TinyFetchSettings settings = settingsRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException(
                        "TinyFish Fetch is not configured. Configure it in Settings > Integrations, "
                                + "or ask the user to paste the page content manually."));
        if (!settings.isEnabled()) {
            throw new BadRequestException(
                    "TinyFish Fetch is disabled for this account. Enable it in Settings > Integrations, "
                            + "or ask the user to paste the page content manually.");
        }
        String url = canonical(urlValidator.validate(rawUrl));
        String key = userId + "|" + url;

        if (!bypassCache) {
            CachedResult hit = cache.get(key);
            if (hit != null && hit.expiresAt().isAfter(clock.instant())) {
                meters.counter("integrations.tinyfetch.requests", "outcome", "cache_hit").increment();
                return hit.response().withCached(true);
            }
        }

        CompletableFuture<FetchUrlResponse> mine = new CompletableFuture<>();
        CompletableFuture<FetchUrlResponse> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            meters.counter("integrations.tinyfetch.requests", "outcome", "deduplicated").increment();
            return await(existing);
        }
        try {
            FetchUrlResponse response = doFetch(userId, settings, url);
            if (response.status() == Status.OK || response.status() == Status.PARTIAL) {
                cache.put(key, new CachedResult(response, clock.instant().plus(properties.getCacheTtl())));
            }
            mine.complete(response);
            return response;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private FetchUrlResponse doFetch(UUID userId, TinyFetchSettings settings, String url) {
        usageTracker.reserve(userId, settings.getPerMinuteLimit(), settings.getDailyLimit());
        String apiKey = cipher.decrypt(settings.getApiKeyEncrypted());
        long started = System.nanoTime();
        String outcomeTag = "error";
        try {
            TinyFetchClient.FetchOutcome outcome = client.fetch(apiKey, url);
            FetchUrlResponse response = toFetchResponse(url, outcome);
            outcomeTag = response.status().name().toLowerCase(Locale.ROOT);
            if (response.status() == Status.BLOCKED || response.status() == Status.FAILED) {
                usageTracker.recordFailure(userId);
            }
            return response;
        } catch (TinyFetchException ex) {
            usageTracker.recordFailure(userId);
            outcomeTag = ex.getKind().name().toLowerCase(Locale.ROOT);
            return switch (ex.getKind()) {
                case UNAUTHORIZED -> throw new BadRequestException(
                        "TinyFish rejected the configured API key. Update it in Settings > Integrations.");
                case QUOTA_EXHAUSTED -> throw new IntegrationRateLimitException("TINYFETCH_PROVIDER_QUOTA",
                        "TinyFish account quota is exhausted. Paste the page content manually or retry later.", null);
                case RATE_LIMITED -> throw new IntegrationRateLimitException("TINYFETCH_PROVIDER_RATE_LIMIT",
                        "TinyFish rate limit reached. Retry shortly or paste the page content manually.", ex.getRetryAfter());
                case TIMEOUT, UPSTREAM, REJECTED -> failed(url, Status.FAILED, ex.getMessage()
                        + ". Ask the user to paste the page content manually.");
            };
        } finally {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            meters.counter("integrations.tinyfetch.requests", "outcome", outcomeTag).increment();
            meters.timer("integrations.tinyfetch.latency", "outcome", outcomeTag)
                    .record(Duration.ofMillis(latencyMs));
            log.info("event=TINYFETCH_FETCH userId={} host={} outcome={} latencyMs={}",
                    userId, hostOf(url), outcomeTag, latencyMs);
        }
    }

    private FetchUrlResponse toFetchResponse(String url, TinyFetchClient.FetchOutcome outcome) {
        if (outcome.content() == null) {
            String code = outcome.errorCode() == null ? "no_content" : outcome.errorCode();
            Status status = looksBlocked(code) ? Status.BLOCKED : Status.FAILED;
            String reason = status == Status.BLOCKED
                    ? "The page is blocked or requires login (" + code + ")."
                    : "The page could not be extracted (" + code + ").";
            return failed(url, status, reason + " Ask the user to paste the page content manually.");
        }
        String content = outcome.content();
        boolean truncated = content.length() > properties.getMaxContentChars();
        if (truncated) {
            content = content.substring(0, properties.getMaxContentChars());
        }
        boolean partial = content.strip().length() < MIN_USEFUL_CONTENT_CHARS;
        return new FetchUrlResponse(
                partial ? Status.PARTIAL : Status.OK,
                outcome.url() == null ? url : outcome.url(),
                outcome.finalUrl(),
                outcome.title(),
                content,
                outcome.metadata(),
                content.length(),
                truncated,
                false,
                partial,
                partial ? "Very little content was extracted; the page may require login. "
                        + "Confirm with the user or ask them to paste the content manually." : null,
                FetchUrlResponse.CONTENT_NOTICE,
                LocalDateTime.now(clock));
    }

    private FetchUrlResponse failed(String url, Status status, String message) {
        return new FetchUrlResponse(status, url, null, null, null, Map.of(), 0, false, false, true,
                message, FetchUrlResponse.CONTENT_NOTICE, LocalDateTime.now(clock));
    }

    private static boolean looksBlocked(String code) {
        String normalized = code.toLowerCase(Locale.ROOT);
        return normalized.contains("block") || normalized.contains("login") || normalized.contains("auth")
                || normalized.contains("captcha") || normalized.contains("forbidden") || normalized.contains("403");
    }

    private FetchUrlResponse await(CompletableFuture<FetchUrlResponse> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ServiceUnavailableException("TinyFish Fetch failed");
        }
    }

    private void evictUserCache(UUID userId) {
        String prefix = userId + "|";
        synchronized (cache) {
            cache.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private TinyFetchSettingsResponse toResponse(TinyFetchSettings settings, UUID userId) {
        boolean serverReady = cipher.isConfigured();
        if (settings == null) {
            return new TinyFetchSettingsResponse(false, false, null,
                    properties.getMaxPerMinuteLimit(), properties.getMaxDailyLimit(),
                    properties.getMaxPerMinuteLimit(), properties.getMaxDailyLimit(),
                    0, null, null, null, serverReady);
        }
        return new TinyFetchSettingsResponse(true, settings.isEnabled(), settings.getApiKeyHint(),
                settings.getPerMinuteLimit(), settings.getDailyLimit(),
                properties.getMaxPerMinuteLimit(), properties.getMaxDailyLimit(),
                usageTracker.usedToday(userId), settings.getLastTestedAt(), settings.getLastTestSuccess(),
                settings.getLastTestMessage(), serverReady);
    }

    private static int boundedLimit(int requested, int max, String field) {
        if (requested < 1 || requested > max) {
            throw new BadRequestException(field + " must be between 1 and " + max);
        }
        return requested;
    }

    /** Drops the fragment so {@code #section} variants share one cache entry and one fetch. */
    static String canonical(URI uri) {
        String value = uri.toString();
        int hash = value.indexOf('#');
        return hash >= 0 ? value.substring(0, hash) : value;
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException ex) {
            return "invalid";
        }
    }

    private record CachedResult(FetchUrlResponse response, Instant expiresAt) {}
}
