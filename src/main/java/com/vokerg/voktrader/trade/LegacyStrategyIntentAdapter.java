package com.vokerg.voktrader.trade;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Temporary compatibility adapter for legacy strategies. Production legacy
 * entries use the same typed central entry boundary as Strategy V2; exits use
 * the separate risk-reducing exit boundary.
 */
@Component
public class LegacyStrategyIntentAdapter {
    private final EntryAcceptanceService entryAcceptanceService;
    private final ExitSubmissionService exitSubmissionService;

    @Autowired
    public LegacyStrategyIntentAdapter(
            EntryAcceptanceService entryAcceptanceService,
            ExitSubmissionService exitSubmissionService
    ) {
        this.entryAcceptanceService = entryAcceptanceService;
        this.exitSubmissionService = exitSubmissionService;
    }

    /**
     * Keeps package-level legacy tests source-compatible. Production wiring uses
     * the typed constructor above; this constructor preserves the old test seam.
     */
    @Deprecated
    public LegacyStrategyIntentAdapter(ExecutionRouter executionRouter) {
        this(
                intent -> executionRouter.route(intent.tradeIntent()),
                intent -> executionRouter.route(intent.tradeIntent())
        );
    }

    public TradeExecutionResult routeEntry(EntryIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return entryAcceptanceService.accept(intent);
    }

    public TradeExecutionResult routeExit(ExitIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return exitSubmissionService.submit(intent);
    }

    /** Compatibility bridge for tests that still pass the former router type. */
    public static LegacyStrategyIntentAdapter fromLegacyRouter(Object candidate) {
        if (!(candidate instanceof ExecutionRouter executionRouter)) {
            throw new IllegalArgumentException("legacy router must be an ExecutionRouter");
        }
        return new LegacyStrategyIntentAdapter(executionRouter);
    }
}
