package com.vokerg.voktrader.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "voktrader.backtest.execution")
public class BacktestExecutionProperties {
    private String fillModel = "maker_cross_pessimistic";
    private int defaultGtdSeconds = 8;
    private boolean allowPartialFills = false;

    public String getFillModel() {
        return fillModel;
    }

    public void setFillModel(String fillModel) {
        this.fillModel = fillModel;
    }

    public int getDefaultGtdSeconds() {
        return defaultGtdSeconds;
    }

    public void setDefaultGtdSeconds(int defaultGtdSeconds) {
        this.defaultGtdSeconds = defaultGtdSeconds;
    }

    public boolean isAllowPartialFills() {
        return allowPartialFills;
    }

    public void setAllowPartialFills(boolean allowPartialFills) {
        this.allowPartialFills = allowPartialFills;
    }

    public BacktestFillModel fillModel() {
        return BacktestFillModel.parse(fillModel);
    }
}
