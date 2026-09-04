package com.vokerg.voktrader.trade.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "voktrader.order-outbox")
public class OrderOutboxProperties {
    private boolean enabled = true;
    private int batchSize = 4;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private long pollMs = 1000L;
    private String workerId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return Math.max(1, batchSize);
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLeaseDuration() {
        return leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                ? Duration.ofSeconds(30)
                : leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public long getPollMs() {
        return Math.max(100L, pollMs);
    }

    public void setPollMs(long pollMs) {
        this.pollMs = pollMs;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId == null ? "" : workerId.trim();
    }
}
