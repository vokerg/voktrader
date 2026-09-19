package com.vokerg.voktrader.polymarket.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "voktrader.user-websocket")
public class UserWebSocketProperties {
    private boolean enabled;
    private String url = "wss://ws-subscriptions-clob.polymarket.com/ws/user";
    private String apiKey;
    private String apiSecret;
    private String apiPassphrase;
    private Duration pingInterval = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getApiPassphrase() {
        return apiPassphrase;
    }

    public void setApiPassphrase(String apiPassphrase) {
        this.apiPassphrase = apiPassphrase;
    }

    public Duration getPingInterval() {
        return pingInterval;
    }

    public void setPingInterval(Duration pingInterval) {
        this.pingInterval = pingInterval;
    }

    public boolean credentialsConfigured() {
        return configured(apiKey) && configured(apiSecret) && configured(apiPassphrase);
    }

    public Duration effectivePingInterval() {
        return pingInterval == null || pingInterval.isZero() || pingInterval.isNegative()
                ? Duration.ofSeconds(10)
                : pingInterval;
    }

    private boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
