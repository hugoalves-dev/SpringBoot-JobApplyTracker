package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.dto.integrations.FetchUrlResponse;
import com.jobtracker.dto.integrations.TinyFetchSettingsRequest;
import com.jobtracker.dto.integrations.TinyFetchSettingsResponse;
import com.jobtracker.entity.User;
import com.jobtracker.entity.integrations.TinyFetchSettings;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.IntegrationRateLimitException;
import com.jobtracker.repository.integrations.TinyFetchSettingsRepository;
import com.jobtracker.service.integrations.PublicUrlValidator;
import com.jobtracker.service.integrations.SecretCipher;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchClient;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchClient.FetchOutcome;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchException;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchService;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchUsageTracker;
import com.jobtracker.util.SecurityUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TinyFetchServiceTest {

    private static final String URL = "https://jobs.example.com/view/1";
    private static final String LONG_CONTENT = "# Senior Java Developer\n" + "Spring Boot, PostgreSQL. ".repeat(20);

    private final TinyFetchSettingsRepository settingsRepository = mock(TinyFetchSettingsRepository.class);
    private final TinyFetchUsageTracker usageTracker = mock(TinyFetchUsageTracker.class);
    private final TinyFetchClient client = mock(TinyFetchClient.class);
    private final SecurityUtils securityUtils = mock(SecurityUtils.class);
    private final SecretCipher cipher = SecretCipherTest.cipher(SecretCipherTest.KEY);
    private final IntegrationProperties properties = new IntegrationProperties();
    private final Map<UUID, TinyFetchSettings> settingsByUser = new HashMap<>();
    private final User user = user();
    private TinyFetchService service;

    @BeforeEach
    void setUp() {
        properties.getTinyfetch().setMaxContentChars(1_000);
        PublicUrlValidator validator = new PublicUrlValidator(host -> host.endsWith("example.com")
                ? new InetAddress[]{InetAddress.getByName("93.184.216.34")}
                : InetAddress.getAllByName(host));
        service = new TinyFetchService(settingsRepository, usageTracker, client, cipher, validator, securityUtils,
                properties, new SimpleMeterRegistry(), Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC));
        actAs(user);
        when(settingsRepository.findByUserId(any())).thenAnswer(inv -> Optional.ofNullable(settingsByUser.get(inv.getArgument(0))));
        when(settingsRepository.save(any())).thenAnswer(inv -> {
            TinyFetchSettings saved = inv.getArgument(0);
            settingsByUser.put(saved.getUser().getId(), saved);
            return saved;
        });
    }

    @Test
    void storesKeyEncryptedAndNeverReturnsIt() {
        TinyFetchSettingsResponse response = service.updateSettings(new TinyFetchSettingsRequest("tf_live_abcdef1234", null, null, null));

        TinyFetchSettings stored = settingsByUser.get(user.getId());
        assertThat(stored.getApiKeyEncrypted()).doesNotContain("tf_live_abcdef1234");
        assertThat(cipher.decrypt(stored.getApiKeyEncrypted())).isEqualTo("tf_live_abcdef1234");
        assertThat(response.toString()).doesNotContain("tf_live_abcdef1234");
        assertThat(response.apiKeyHint()).isEqualTo("••••1234");
        assertThat(response.perMinuteLimit()).isEqualTo(150);
        assertThat(response.dailyLimit()).isEqualTo(1000);
    }

    @Test
    void requiresKeyOnFirstSetupAndBoundsLimitsToProviderQuota() {
        assertThatThrownBy(() -> service.updateSettings(new TinyFetchSettingsRequest(null, true, null, null)))
                .isInstanceOf(BadRequestException.class);

        service.updateSettings(new TinyFetchSettingsRequest("key-123456789", null, 30, 200));
        assertThat(settingsByUser.get(user.getId()).getDailyLimit()).isEqualTo(200);

        assertThatThrownBy(() -> service.updateSettings(new TinyFetchSettingsRequest(null, null, 151, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateSettings(new TinyFetchSettingsRequest(null, null, null, 1001)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void failsClearlyWhenNotConfiguredOrDisabled() {
        assertThatThrownBy(() -> service.fetch(URL, false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("paste");

        configure(user, false);
        assertThatThrownBy(() -> service.fetch(URL, false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("disabled");
        verify(client, never()).fetch(anyString(), anyString());
    }

    @Test
    void returnsMarkdownWithUntrustedNoticeAndCachesPerUser() {
        configure(user, true);
        when(client.fetch("tf-key-123456", URL)).thenReturn(page(LONG_CONTENT));

        FetchUrlResponse first = service.fetch(URL + "#apply", false);
        FetchUrlResponse second = service.fetch(URL, false);

        assertThat(first.status()).isEqualTo(FetchUrlResponse.Status.OK);
        assertThat(first.title()).isEqualTo("Senior Java Developer");
        assertThat(first.contentNotice()).contains("Untrusted");
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        verify(client, times(1)).fetch(anyString(), anyString());
        verify(usageTracker, times(1)).reserve(eq(user.getId()), anyInt(), anyInt());

        User other = user();
        actAs(other);
        configure(other, true);
        service.fetch(URL, false);
        verify(client, times(2)).fetch(anyString(), anyString());
    }

    @Test
    void bypassCacheFetchesAgain() {
        configure(user, true);
        when(client.fetch(anyString(), anyString())).thenReturn(page(LONG_CONTENT));

        service.fetch(URL, false);
        service.fetch(URL, true);

        verify(client, times(2)).fetch(anyString(), anyString());
    }

    @Test
    void blockedPagesAskForManualInputAndAreNotCached() {
        configure(user, true);
        when(client.fetch(anyString(), anyString()))
                .thenReturn(new FetchOutcome(null, null, null, null, Map.of(), "login_required"));

        FetchUrlResponse response = service.fetch(URL, false);
        service.fetch(URL, false);

        assertThat(response.status()).isEqualTo(FetchUrlResponse.Status.BLOCKED);
        assertThat(response.manualInputRequired()).isTrue();
        assertThat(response.markdown()).isNull();
        verify(client, times(2)).fetch(anyString(), anyString());
        verify(usageTracker, times(2)).recordFailure(user.getId());
    }

    @Test
    void thinContentIsPartialAndLongContentIsTruncated() {
        configure(user, true);
        when(client.fetch(anyString(), eq(URL))).thenReturn(page("Sign in to view"));
        when(client.fetch(anyString(), eq(URL + "2"))).thenReturn(page("x".repeat(5_000)));

        FetchUrlResponse thin = service.fetch(URL, false);
        FetchUrlResponse longOne = service.fetch(URL + "2", false);

        assertThat(thin.status()).isEqualTo(FetchUrlResponse.Status.PARTIAL);
        assertThat(thin.manualInputRequired()).isTrue();
        assertThat(longOne.truncated()).isTrue();
        assertThat(longOne.markdown()).hasSize(1_000);
    }

    @Test
    void timeoutsBecomeManualFallbackWhileAuthAndQuotaErrorsSurface() {
        configure(user, true);
        when(client.fetch(anyString(), eq(URL)))
                .thenThrow(new TinyFetchException(TinyFetchException.Kind.TIMEOUT, "TinyFish Fetch did not respond in time", null));
        when(client.fetch(anyString(), eq(URL + "/auth")))
                .thenThrow(new TinyFetchException(TinyFetchException.Kind.UNAUTHORIZED, "bad key", null));
        when(client.fetch(anyString(), eq(URL + "/rate")))
                .thenThrow(new TinyFetchException(TinyFetchException.Kind.RATE_LIMITED, "429", null));

        FetchUrlResponse timeout = service.fetch(URL, false);
        assertThat(timeout.status()).isEqualTo(FetchUrlResponse.Status.FAILED);
        assertThat(timeout.manualInputRequired()).isTrue();

        assertThatThrownBy(() -> service.fetch(URL + "/auth", false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("API key");
        assertThatThrownBy(() -> service.fetch(URL + "/rate", false))
                .isInstanceOf(IntegrationRateLimitException.class);
    }

    @Test
    void quotaExhaustionStopsBeforeCallingProvider() {
        configure(user, true);
        doThrow(new IntegrationRateLimitException("TINYFETCH_DAILY_LIMIT", "limit", null))
                .when(usageTracker).reserve(any(), anyInt(), anyInt());

        assertThatThrownBy(() -> service.fetch(URL, false)).isInstanceOf(IntegrationRateLimitException.class);
        verify(client, never()).fetch(anyString(), anyString());
    }

    @Test
    void rejectsPrivateTargetsBeforeSpendingQuota() {
        configure(user, true);

        assertThatThrownBy(() -> service.fetch("http://169.254.169.254/latest", false))
                .isInstanceOf(BadRequestException.class);
        verify(usageTracker, never()).reserve(any(), anyInt(), anyInt());
    }

    @Test
    void concurrentIdenticalRequestsShareOneProviderCall() throws Exception {
        configure(user, true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.fetch(anyString(), anyString())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return page(LONG_CONTENT);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<FetchUrlResponse> first = pool.submit(() -> service.fetch(URL, true));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<FetchUrlResponse> second = pool.submit(() -> service.fetch(URL, true));
            Thread.sleep(100);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(FetchUrlResponse.Status.OK);
            assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(FetchUrlResponse.Status.OK);
        } finally {
            pool.shutdownNow();
        }
        verify(client, times(1)).fetch(anyString(), anyString());
    }

    private void configure(User owner, boolean enabled) {
        TinyFetchSettings settings = new TinyFetchSettings();
        settings.setUser(owner);
        settings.setApiKeyEncrypted(cipher.encrypt("tf-key-123456"));
        settings.setEnabled(enabled);
        settings.setPerMinuteLimit(10);
        settings.setDailyLimit(100);
        settingsByUser.put(owner.getId(), settings);
    }

    private void actAs(User current) {
        when(securityUtils.getCurrentUser()).thenReturn(current);
        when(securityUtils.getCurrentUserId()).thenReturn(current.getId());
    }

    private static FetchOutcome page(String content) {
        return new FetchOutcome(URL, URL, "Senior Java Developer", content, Map.of("language", "en"), null);
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }
}
