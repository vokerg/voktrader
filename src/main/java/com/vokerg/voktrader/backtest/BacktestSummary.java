package com.vokerg.voktrader.backtest;

import java.math.BigDecimal;

public record BacktestSummary(
        long tradeCount,
        long closedTradeCount,
        long openTradeCount,
        BigDecimal totalFeeUsd,
        BigDecimal finalPnlUsd,
        BacktestOrderMetrics orderMetrics
) {
    public static BacktestSummary empty() {
        return new BacktestSummary(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BacktestOrderMetrics.empty());
    }
}
