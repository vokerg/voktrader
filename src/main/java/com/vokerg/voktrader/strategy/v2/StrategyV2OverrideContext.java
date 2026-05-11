package com.vokerg.voktrader.strategy.v2;

import java.util.Optional;

public final class StrategyV2OverrideContext {
    private static final ThreadLocal<StrategyV2Properties> CURRENT = new ThreadLocal<>();

    private StrategyV2OverrideContext() {
    }

    public static Optional<StrategyV2Properties> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void runWith(StrategyV2Properties properties, Runnable runnable) {
        StrategyV2Properties previous = CURRENT.get();
        CURRENT.set(properties);
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
