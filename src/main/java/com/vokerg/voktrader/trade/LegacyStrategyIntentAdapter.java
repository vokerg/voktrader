package com.vokerg.voktrader.trade;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Temporary compatibility adapter for legacy strategies that still use the
 * pre-order-layer execution semantics. Legacy strategy code remains typed and
 * the generic router is isolated here until those strategies are retired.
 */
@Component
public class LegacyStrategyIntentAdapter {
    private final ExecutionRouter executionRouter;

    public LegacyStrategyIntentAdapter(ExecutionRouter executionRouter) {
        this.executionRouter = executionRouter;
    }

    public TradeExecutionResult routeEntry(EntryIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return executionRouter.route(intent.tradeIntent());
    }

    public TradeExecutionResult routeExit(ExitIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return executionRouter.route(intent.tradeIntent());
    }

    /**
     * Keeps package-level legacy tests source-compatible without exposing
     * ExecutionRouter in strategy production fields or imports.
     */
    public static LegacyStrategyIntentAdapter fromLegacyRouter(Object candidate) {
        if (!(candidate instanceof ExecutionRouter executionRouter)) {
            throw new IllegalArgumentException("legacy router must be an ExecutionRouter");
        }
        return new LegacyStrategyIntentAdapter(executionRouter);
    }
}
