package com.vokerg.voktrader.trade.paper;

import com.vokerg.voktrader.backtest.BacktestFillModel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "voktrader.paper.execution")
public class PaperExecutionProperties {
    private String fillModel = "maker_cross_pessimistic";
    private int defaultGtdSeconds = 8;
    private boolean allowPartialFills = true;
    private BigDecimal makerTouchFillRatio = new BigDecimal("0.25");
    private boolean advanceOpenOrdersOnBookUpdate = true;

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

    public BigDecimal getMakerTouchFillRatio() {
        return makerTouchFillRatio;
    }

    public void setMakerTouchFillRatio(BigDecimal makerTouchFillRatio) {
        this.makerTouchFillRatio = makerTouchFillRatio;
    }

    public boolean isAdvanceOpenOrdersOnBookUpdate() {
        return advanceOpenOrdersOnBookUpdate;
    }

    public void setAdvanceOpenOrdersOnBookUpdate(boolean advanceOpenOrdersOnBookUpdate) {
        this.advanceOpenOrdersOnBookUpdate = advanceOpenOrdersOnBookUpdate;
    }

    public BigDecimal makerTouchFillRatio() {
        if (makerTouchFillRatio == null || makerTouchFillRatio.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (makerTouchFillRatio.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return makerTouchFillRatio;
    }

    public BacktestFillModel fillModel() {
        return BacktestFillModel.parse(fillModel);
    }
}
