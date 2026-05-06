package com.vokerg.voktrader.trade;

public final class ExecutionOverrideContext {
    private static final ThreadLocal<TradeIntentExecutor> CURRENT = new ThreadLocal<>();

    private ExecutionOverrideContext() {
    }

    public static TradeIntentExecutor current() {
        return CURRENT.get();
    }

    public static void runWith(TradeIntentExecutor executor, Runnable runnable) {
        TradeIntentExecutor previous = CURRENT.get();
        CURRENT.set(executor);
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
