package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Carries a successful central risk decision through the existing synchronous
 * execution adapters. This is deliberately package-private: production entry
 * code can obtain approval only through {@link StrategyIntentBoundary}.
 */
final class EntryRiskDecisionContext {
    private static final ThreadLocal<Decision> CURRENT = new ThreadLocal<>();

    private EntryRiskDecisionContext() {
    }

    static <T> T withApproved(EntryRiskRequest request, RiskAssessment assessment, Supplier<T> action) {
        if (!assessment.passed()) {
            throw new IllegalArgumentException("blocked entry risk assessment cannot be approved");
        }
        Decision previous = CURRENT.get();
        CURRENT.set(new Decision(request, assessment));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static Optional<Decision> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static boolean approves(TradeIntent intent, ExecutionMode mode) {
        return current().map(decision -> decision.request().matches(intent, mode)).orElse(false);
    }

    record Decision(EntryRiskRequest request, RiskAssessment assessment) {
    }
}
