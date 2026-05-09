package com.vokerg.voktrader.trade;

import java.util.Optional;

public final class BacktestTradeStateContext {
    private static final ThreadLocal<TradeStateProvider> CURRENT = new ThreadLocal<>();

    private BacktestTradeStateContext() {
    }

    public static Optional<TradeStateProvider> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void runWith(TradeStateProvider provider, Runnable runnable) {
        TradeStateProvider previous = CURRENT.get();
        CURRENT.set(provider);
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
