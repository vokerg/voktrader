package com.vokerg.voktrader.backtest;

import java.math.BigDecimal;
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
        BigDecimal totalFeeUsd,
        BigDecimal finalPnlUsd,
        Map<String, Long> eventCounts,
        Map<String, Long> entryRejectReasons,
        Map<String, Long> executionRejectReasons,
        Map<String, Long> strategySkipReasons
) {
}
