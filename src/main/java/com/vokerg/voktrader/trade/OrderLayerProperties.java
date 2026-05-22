package com.vokerg.voktrader.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "voktrader.order-layer")
public class OrderLayerProperties {
    private boolean enabled = false;
    private Reconciliation reconciliation = new Reconciliation();
    private int maxReconcileAgeMinutes = 120;
    private long fillLookupLookbackSeconds = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Reconciliation getReconciliation() {
        return reconciliation;
    }

    public void setReconciliation(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    public int getMaxReconcileAgeMinutes() {
        return maxReconcileAgeMinutes;
    }

    public void setMaxReconcileAgeMinutes(int maxReconcileAgeMinutes) {
        this.maxReconcileAgeMinutes = maxReconcileAgeMinutes;
    }

    public long getFillLookupLookbackSeconds() {
        return fillLookupLookbackSeconds;
    }

    public void setFillLookupLookbackSeconds(long fillLookupLookbackSeconds) {
        this.fillLookupLookbackSeconds = fillLookupLookbackSeconds;
    }

    public static class Reconciliation {
        private boolean enabled = false;
        private long intervalMs = 2000;
        private long initialBackoffSeconds = 5;
        private long maxBackoffSeconds = 300;
        private int pauseAfterConsecutiveFailures = 5;
        private int maxOrdersPerCycle = 50;
        private boolean noProgressEnabled = true;
        private long noProgressInitialBackoffSeconds = 5;
        private long noProgressMaxBackoffSeconds = 120;
        private int pauseAfterConsecutiveNoProgress = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getInitialBackoffSeconds() {
            return initialBackoffSeconds;
        }

        public void setInitialBackoffSeconds(long initialBackoffSeconds) {
            this.initialBackoffSeconds = initialBackoffSeconds;
        }

        public long getMaxBackoffSeconds() {
            return maxBackoffSeconds;
        }

        public void setMaxBackoffSeconds(long maxBackoffSeconds) {
            this.maxBackoffSeconds = maxBackoffSeconds;
        }

        public int getPauseAfterConsecutiveFailures() {
            return pauseAfterConsecutiveFailures;
        }

        public void setPauseAfterConsecutiveFailures(int pauseAfterConsecutiveFailures) {
            this.pauseAfterConsecutiveFailures = pauseAfterConsecutiveFailures;
        }

        public int getMaxOrdersPerCycle() {
            return maxOrdersPerCycle;
        }

        public void setMaxOrdersPerCycle(int maxOrdersPerCycle) {
            this.maxOrdersPerCycle = maxOrdersPerCycle;
        }

        public boolean isNoProgressEnabled() {
            return noProgressEnabled;
        }

        public void setNoProgressEnabled(boolean noProgressEnabled) {
            this.noProgressEnabled = noProgressEnabled;
        }

        public long getNoProgressInitialBackoffSeconds() {
            return noProgressInitialBackoffSeconds;
        }

        public void setNoProgressInitialBackoffSeconds(long noProgressInitialBackoffSeconds) {
            this.noProgressInitialBackoffSeconds = noProgressInitialBackoffSeconds;
        }

        public long getNoProgressMaxBackoffSeconds() {
            return noProgressMaxBackoffSeconds;
        }

        public void setNoProgressMaxBackoffSeconds(long noProgressMaxBackoffSeconds) {
            this.noProgressMaxBackoffSeconds = noProgressMaxBackoffSeconds;
        }

        public int getPauseAfterConsecutiveNoProgress() {
            return pauseAfterConsecutiveNoProgress;
        }

        public void setPauseAfterConsecutiveNoProgress(int pauseAfterConsecutiveNoProgress) {
            this.pauseAfterConsecutiveNoProgress = pauseAfterConsecutiveNoProgress;
        }
    }
}
