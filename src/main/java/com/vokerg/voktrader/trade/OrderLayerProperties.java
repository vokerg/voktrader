package com.vokerg.voktrader.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "voktrader.order-layer")
public class OrderLayerProperties {
    private boolean enabled = false;
    private Reconciliation reconciliation = new Reconciliation();
    private int maxReconcileAgeMinutes = 120;

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

    public static class Reconciliation {
        private boolean enabled = false;
        private long intervalMs = 2000;

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
    }
}
