package com.vokerg.voktrader.backtest;

import java.math.BigDecimal;

public record BacktestOrderMetrics(
        long submittedOrders,
        long filledOrders,
        long partiallyFilledOrders,
        long expiredOrders,
        long cancelledOrders,
        long rejectedOrders,
        BigDecimal fillRate,
        BigDecimal partialFillRate,
        BigDecimal averageRestingSeconds,
        BigDecimal estimatedFeesUsd,
        long makerFills,
        long takerFills,
        long entryPendingAtEnd,
        long exitPendingAtEnd
) {
    public static BacktestOrderMetrics empty() {
        return new BacktestOrderMetrics(
                0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0, 0, 0, 0
        );
    }
}
