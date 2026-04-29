package com.vokerg.voktrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "voktrader.market")
public record MarketSelectionProperties(
        String interval,
        Long minSecondsRemaining,
        Long maxSecondsRemaining,
        Long expiryGraceSeconds
) {
    public String intervalOrDefault() {
        if (interval == null || interval.isBlank()) {
            return "15m";
        }

        return interval.trim().toLowerCase();
    }

    public Duration minRemaining() {
        return Duration.ofSeconds(minSecondsRemaining == null ? 300 : minSecondsRemaining);
    }

    public Duration maxRemaining() {
        return Duration.ofSeconds(maxSecondsRemaining == null ? 720 : maxSecondsRemaining);
    }

    public Duration expiryGrace() {
        return Duration.ofSeconds(expiryGraceSeconds == null ? 15 : expiryGraceSeconds);
    }
}
