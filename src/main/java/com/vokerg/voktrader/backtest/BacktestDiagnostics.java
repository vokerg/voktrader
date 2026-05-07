package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.telemetry.TradingEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public class BacktestDiagnostics {
    private final Map<String, Long> eventCounts = new LinkedHashMap<>();
    private final Map<String, Long> entryRejectReasons = new LinkedHashMap<>();
    private final Map<String, Long> executionRejectReasons = new LinkedHashMap<>();
    private final Map<String, Long> strategySkipReasons = new LinkedHashMap<>();

    public void record(TradingEvent event) {
        if (event == null) {
            return;
        }
        increment(eventCounts, event.type());
        if ("ENTRY_REJECTED".equals(event.type())) {
            increment(entryRejectReasons, event.reason());
        }
        if ("TRADE_REJECTED".equals(event.type())) {
            increment(executionRejectReasons, event.reason());
        }
    }

    public Map<String, Long> eventCounts() {
        return Map.copyOf(eventCounts);
    }

    public Map<String, Long> entryRejectReasons() {
        return Map.copyOf(entryRejectReasons);
    }

    public Map<String, Long> executionRejectReasons() {
        return Map.copyOf(executionRejectReasons);
    }

    public void recordStrategySkip(String strategyId, String reason) {
        increment(strategySkipReasons, (strategyId == null ? "(missing)" : strategyId) + ": " + reason);
    }

    public Map<String, Long> strategySkipReasons() {
        return Map.copyOf(strategySkipReasons);
    }

    private void increment(Map<String, Long> counts, String key) {
        String safeKey = key == null || key.isBlank() ? "(missing)" : key;
        counts.merge(safeKey, 1L, Long::sum);
    }
}
