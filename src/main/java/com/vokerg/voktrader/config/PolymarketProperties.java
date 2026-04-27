package com.vokerg.voktrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymarket")
public record PolymarketProperties(
        String gammaBaseUrl,
        String clobBaseUrl,
        String marketWsUrl,
        Integer requestTimeoutSeconds
) {
    public int timeoutSeconds() {
        return requestTimeoutSeconds == null ? 10 : requestTimeoutSeconds;
    }
}