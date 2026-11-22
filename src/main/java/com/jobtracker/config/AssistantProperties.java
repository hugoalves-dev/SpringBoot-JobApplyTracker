package com.jobtracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.ZoneId;

@Component
@ConfigurationProperties(prefix = "app.assistant")
public class AssistantProperties {
    private boolean enabled;
    private int maxMessageLength = 4000;
    private int defaultSearchResults = 10;
    private int maxSearchResults = 20;
    private int memoryMaxMessages = 20;
    private Duration streamTimeout = Duration.ofSeconds(60);
    private int maxOutputTokens = 1024;
    private int maxToolCalls = 8;
    private final Quota quota = new Quota();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxMessageLength() { return maxMessageLength; }
    public void setMaxMessageLength(int value) { this.maxMessageLength = value; }
    public int getDefaultSearchResults() { return defaultSearchResults; }
    public void setDefaultSearchResults(int value) { this.defaultSearchResults = value; }
    public int getMaxSearchResults() { return maxSearchResults; }
    public void setMaxSearchResults(int value) { this.maxSearchResults = value; }
    public int getMemoryMaxMessages() { return memoryMaxMessages; }
    public void setMemoryMaxMessages(int value) { this.memoryMaxMessages = value; }
    public Duration getStreamTimeout() { return streamTimeout; }
    public void setStreamTimeout(Duration value) { this.streamTimeout = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { this.maxOutputTokens = value; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int value) { this.maxToolCalls = value; }
    public Quota getQuota() { return quota; }

    public int clampLimit(Integer requested) {
        int value = requested == null ? defaultSearchResults : requested;
        return Math.max(1, Math.min(value, maxSearchResults));
    }

    /**
     * Provider-agnostic usage limits enforced before any ChatModel call.
     * A limit of 0 disables that specific check.
     */
    public static class Quota {
        private boolean enabled = true;
        private int dailyLimit = 30;
        private ZoneId resetZone = ZoneId.of("America/Sao_Paulo");
        private int rateLimit = 5;
        private Duration rateWindow = Duration.ofMinutes(1);
        private int maxConcurrent = 1;
        private int globalDailyLimit = 500;
        private int globalRateLimit = 20;
        private Duration globalRateWindow = Duration.ofMinutes(1);
        private Duration inFlightLeaseMargin = Duration.ofSeconds(30);
        private Duration retention = Duration.ofDays(7);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(int value) { this.dailyLimit = value; }
        public ZoneId getResetZone() { return resetZone; }
        public void setResetZone(ZoneId value) { this.resetZone = value; }
        public int getRateLimit() { return rateLimit; }
        public void setRateLimit(int value) { this.rateLimit = value; }
        public Duration getRateWindow() { return rateWindow; }
        public void setRateWindow(Duration value) { this.rateWindow = value; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int value) { this.maxConcurrent = value; }
        public int getGlobalDailyLimit() { return globalDailyLimit; }
        public void setGlobalDailyLimit(int value) { this.globalDailyLimit = value; }
        public int getGlobalRateLimit() { return globalRateLimit; }
        public void setGlobalRateLimit(int value) { this.globalRateLimit = value; }
        public Duration getGlobalRateWindow() { return globalRateWindow; }
        public void setGlobalRateWindow(Duration value) { this.globalRateWindow = value; }
        public Duration getInFlightLeaseMargin() { return inFlightLeaseMargin; }
        public void setInFlightLeaseMargin(Duration value) { this.inFlightLeaseMargin = value; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration value) { this.retention = value; }
    }
}
