package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.telemetry.TradingEvent;

public final class BacktestDiagnosticsContext {
    private static final ThreadLocal<BacktestDiagnostics> CURRENT = new ThreadLocal<>();

    private BacktestDiagnosticsContext() {
    }

    public static void record(TradingEvent event) {
        BacktestDiagnostics diagnostics = CURRENT.get();
        if (diagnostics != null) {
            diagnostics.record(event);
        }
    }

    public static void recordStrategySkip(String strategyId, String reason) {
        BacktestDiagnostics diagnostics = CURRENT.get();
        if (diagnostics != null) {
            diagnostics.recordStrategySkip(strategyId, reason);
        }
    }

    public static void runWith(BacktestDiagnostics diagnostics, Runnable runnable) {
        BacktestDiagnostics previous = CURRENT.get();
        CURRENT.set(diagnostics);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
