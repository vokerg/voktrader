package com.vokerg.voktrader.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "voktrader.executor")
public class ExecutorProperties {
    /**
     * Keep false unless the Python sidecar is explicitly running and configured.
     */
    private boolean enabled = false;

    /**
     * When true, Java sends dry-run orders and the sidecar does not touch the exchange.
     */
    private boolean dryRun = true;

    /**
     * Sidecar base URL, for example http://127.0.0.1:8099.
     */
    private String baseUrl = "http://127.0.0.1:8099";

    /**
     * Shared bearer token between the JVM app and the sidecar.
     */
    private String apiToken = "change-me";

    /**
     * Executor request timeout. Polymarket order placement should normally be fast.
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * The current trade lifecycle has no async reconciliation worker, so default to immediate fills only.
     */
    private boolean requireImmediateFill = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isRequireImmediateFill() {
        return requireImmediateFill;
    }

    public void setRequireImmediateFill(boolean requireImmediateFill) {
        this.requireImmediateFill = requireImmediateFill;
    }
}
