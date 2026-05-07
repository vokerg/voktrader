package com.vokerg.voktrader.trade;

import java.util.Optional;

public final class TradeHistoryScopeContext {
    private static final ThreadLocal<String> BACKTEST_RUN_ID = new ThreadLocal<>();

    private TradeHistoryScopeContext() {
    }

    public static Optional<String> currentBacktestRunId() {
        return Optional.ofNullable(BACKTEST_RUN_ID.get());
    }

    public static void runWithBacktestRunId(String runId, Runnable runnable) {
        String previous = BACKTEST_RUN_ID.get();
        BACKTEST_RUN_ID.set(runId);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                BACKTEST_RUN_ID.remove();
            } else {
                BACKTEST_RUN_ID.set(previous);
            }
        }
    }
}
