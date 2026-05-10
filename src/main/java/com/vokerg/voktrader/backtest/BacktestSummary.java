package com.vokerg.voktrader.backtest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record BacktestSummary(
        long tradeCount,
        long closedTradeCount,
        long openTradeCount,
        long resolvedWinningTradeCount,
        long resolvedLosingTradeCount,
        BigDecimal totalEntryUsd,
        BigDecimal totalExitUsd,
        BigDecimal totalFeeUsd,
        BigDecimal grossPnlUsd,
        BigDecimal finalPnlUsd,
        BigDecimal netRoiPct,
        Instant replayStartedAt,
        Instant replayEndedAt,
        Long replayDurationSeconds,
        Long wallClockDurationMs,
        BacktestOrderMetrics orderMetrics
) {
    public static BacktestSummary empty() {
        return new BacktestSummary(
                0,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                BacktestOrderMetrics.empty()
        );
    }

    public static Long durationSeconds(Instant start, Instant end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toSeconds();
    }
}
