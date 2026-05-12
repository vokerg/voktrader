package com.vokerg.voktrader.strategy.v2;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class StrategyV2FeatureSampleContext {
    private static final ThreadLocal<Map<String, ArrayDeque<StrategyV2FeatureResolver.Sample>>> CURRENT = new ThreadLocal<>();

    private StrategyV2FeatureSampleContext() {
    }

    static Optional<Map<String, ArrayDeque<StrategyV2FeatureResolver.Sample>>> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void runIsolated(Runnable runnable) {
        Map<String, ArrayDeque<StrategyV2FeatureResolver.Sample>> previous = CURRENT.get();
        CURRENT.set(new ConcurrentHashMap<>());
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
