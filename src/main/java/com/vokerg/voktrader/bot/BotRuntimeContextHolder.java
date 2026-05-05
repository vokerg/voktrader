package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.pricing.LatestPriceState;

import java.util.Optional;

public final class BotRuntimeContextHolder {
    private static final ThreadLocal<BotRuntimeContext> CURRENT = new ThreadLocal<>();

    private BotRuntimeContextHolder() {
    }

    public static Optional<BotRuntimeContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<Long> currentBotId() {
        return current().map(BotRuntimeContext::botId);
    }

    public static Optional<LatestPriceState> currentLatestPriceState() {
        return current().map(BotRuntimeContext::latestPriceState);
    }

    public static Optional<TrackedMarketState> currentTrackedMarketState() {
        return current().map(BotRuntimeContext::trackedMarketState);
    }

    public static void runWith(BotRuntimeContext context, Runnable runnable) {
        BotRuntimeContext previous = CURRENT.get();
        CURRENT.set(context);
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
