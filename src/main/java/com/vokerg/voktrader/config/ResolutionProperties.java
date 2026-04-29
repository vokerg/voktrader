package com.vokerg.voktrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "voktrader.resolution")
public record ResolutionProperties(
        Long pollMs,
        Long graceSeconds,
        Integer maxMarketsPerRun
) {
    public long pollMsOrDefault() {
        return pollMs == null ? 30_000L : pollMs;
    }

    public Duration grace() {
        return Duration.ofSeconds(graceSeconds == null ? 30 : graceSeconds);
    }

    public int maxMarketsPerRunOrDefault() {
        return maxMarketsPerRun == null ? 10 : maxMarketsPerRun;
    }
}
