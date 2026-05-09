package com.vokerg.voktrader.strategy.v2;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "voktrader.strategy-v2.execution")
public class StrategyV2ExecutionProperties {
    private boolean useOrderLayer = false;

    public boolean isUseOrderLayer() {
        return useOrderLayer;
    }

    public void setUseOrderLayer(boolean useOrderLayer) {
        this.useOrderLayer = useOrderLayer;
    }
}
