package com.vokerg.voktrader.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BacktestResponse(
        String runId,
        String strategyId,
        List<String> marketIds,
        long snapshotCount,
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
        BacktestOrderMetrics orderMetrics,
        Map<String, Long> eventCounts,
        Map<String, Long> entryRejectReasons,
        Map<String, Long> executionRejectReasons,
        Map<String, Long> strategySkipReasons
) {
}
