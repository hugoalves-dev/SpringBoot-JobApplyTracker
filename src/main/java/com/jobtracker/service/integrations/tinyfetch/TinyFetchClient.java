package com.jobtracker.service.integrations.tinyfetch;

import com.jobtracker.config.IntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for the TinyFish Fetch REST API ({@code POST https://api.fetch.tinyfish.ai}, {@code X-API-Key}).
 * Only URL reading is used: no TinySearch, TinyAgent or browser automation.
 *
 * <p>The response is parsed leniently ({@code results[]} / {@code errors[]}, content in {@code text}
 * with {@code markdown}/{@code content} fallbacks) because the provider may add fields over time.
 */
@Component
public class TinyFetchClient {

    private static final Logger log = LoggerFactory.getLogger(TinyFetchClient.class);
    private static final Set<String> KNOWN_RESULT_FIELDS =
            Set.of("url", "final_url", "finalUrl", "title", "text", "markdown", "content");

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final IntegrationProperties.TinyFetch properties;
    private final RestClient restClient;
    private final Sleeper sleeper;

    @Autowired
    public TinyFetchClient(IntegrationProperties properties, RestClient.Builder builder) {
        this(properties, builder.requestFactory(requestFactory(properties.getTinyfetch())).build(),
                duration -> Thread.sleep(duration.toMillis()));
    }

    public TinyFetchClient(IntegrationProperties properties, RestClient restClient, Sleeper sleeper) {
        this.properties = properties.getTinyfetch();
        this.restClient = restClient;
        this.sleeper = sleeper;
    }

    public FetchOutcome fetch(String apiKey, String url) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            RawResponse raw;
            try {
                raw = execute(apiKey, url);
            } catch (ResourceAccessException ex) {
                if (attempt >= maxAttempts) {
                    throw new TinyFetchException(TinyFetchException.Kind.TIMEOUT,
                            "TinyFish Fetch did not respond in time", null);
                }
                backoff(attempt, null);
                continue;
            } catch (RestClientException ex) {
                throw new TinyFetchException(TinyFetchException.Kind.UPSTREAM,
                        "TinyFish Fetch request failed", null);
            }

            int status = raw.status();
            if (status >= 200 && status < 300) {
                return parse(raw.body(), url);
            }
            if (status == 401 || status == 403) {
                throw new TinyFetchException(TinyFetchException.Kind.UNAUTHORIZED,
                        "TinyFish rejected the API key (HTTP " + status + ")", null);
            }
            if (status == 402) {
                throw new TinyFetchException(TinyFetchException.Kind.QUOTA_EXHAUSTED,
                        "TinyFish account quota or credits are exhausted", null);
            }
            Duration retryAfter = parseRetryAfter(raw.retryAfter());
            boolean retryable = status == 429 || status >= 500;
            boolean retryAfterTooLong = retryAfter != null && retryAfter.compareTo(properties.getMaxRetryDelay()) > 0;
            if (!retryable || attempt >= maxAttempts || retryAfterTooLong) {
                if (status == 429) {
                    throw new TinyFetchException(TinyFetchException.Kind.RATE_LIMITED,
                            "TinyFish rate limit reached", retryAfter);
                }
                if (status >= 500) {
                    throw new TinyFetchException(TinyFetchException.Kind.UPSTREAM,
                            "TinyFish Fetch is unavailable (HTTP " + status + ")", retryAfter);
                }
                throw new TinyFetchException(TinyFetchException.Kind.REJECTED,
                        "TinyFish rejected the request (HTTP " + status + ")", null);
            }
            log.info("event=TINYFETCH_RETRY attempt={} status={}", attempt, status);
            backoff(attempt, retryAfter);
        }
    }

    private RawResponse execute(String apiKey, String url) {
        Map<String, Object> body = Map.of("urls", List.of(url), "format", "markdown");
        return restClient.post()
                .uri(properties.getBaseUrl())
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 200 && status < 300) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = response.bodyTo(Map.class);
                        return new RawResponse(status, parsed, null);
                    }
                    return new RawResponse(status, null, response.getHeaders().getFirst("Retry-After"));
                });
    }

    @SuppressWarnings("unchecked")
    private FetchOutcome parse(Map<String, Object> body, String requestedUrl) {
        if (body == null) {
            return FetchOutcome.failed("empty_response");
        }
        Object results = body.get("results");
        if (results instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            Map<String, Object> result = (Map<String, Object>) first;
            String content = firstText(result, "text", "markdown", "content");
            Map<String, Object> metadata = new LinkedHashMap<>();
            result.forEach((key, value) -> {
                if (!KNOWN_RESULT_FIELDS.contains(key) && isScalar(value)) {
                    metadata.put(key, value);
                }
            });
            return new FetchOutcome(
                    firstText(result, "url") == null ? requestedUrl : firstText(result, "url"),
                    firstText(result, "final_url", "finalUrl"),
                    firstText(result, "title"),
                    content,
                    metadata,
                    null);
        }
        Object errors = body.get("errors");
        if (errors instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            Object code = first.get("error");
            return FetchOutcome.failed(code == null ? "unknown_error" : code.toString());
        }
        return FetchOutcome.failed("no_result");
    }

    private void backoff(int attempt, Duration retryAfter) {
        Duration exponential = properties.getRetryBaseDelay().multipliedBy(1L << Math.min(attempt - 1, 10));
        Duration delay = retryAfter != null ? retryAfter : exponential;
        if (delay.compareTo(properties.getMaxRetryDelay()) > 0) {
            delay = properties.getMaxRetryDelay();
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TinyFetchException(TinyFetchException.Kind.TIMEOUT, "TinyFish Fetch was interrupted", null);
        }
    }

    static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static SimpleClientHttpRequestFactory requestFactory(IntegrationProperties.TinyFetch properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    private record RawResponse(int status, Map<String, Object> body, String retryAfter) {}

    /** {@code errorCode} is set when TinyFish answered but could not extract the page (e.g. bot_blocked). */
    public record FetchOutcome(String url, String finalUrl, String title, String content,
                               Map<String, Object> metadata, String errorCode) {
        static FetchOutcome failed(String errorCode) {
            return new FetchOutcome(null, null, null, null, Map.of(), errorCode);
        }
    }
}
