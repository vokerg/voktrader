package com.vokerg.voktrader.strategy.v2;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voktrader.strategy-v2.diagnostics")
public class StrategyV2DiagnosticsProperties {
    private boolean entryPulseEnabled = false;
    private long entryPulseSeconds = 30;

    public boolean isEntryPulseEnabled() {
        return entryPulseEnabled;
    }

    public void setEntryPulseEnabled(boolean entryPulseEnabled) {
        this.entryPulseEnabled = entryPulseEnabled;
    }

    public long getEntryPulseSeconds() {
        return entryPulseSeconds;
    }

    public void setEntryPulseSeconds(long entryPulseSeconds) {
        this.entryPulseSeconds = entryPulseSeconds;
    }
}
