package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;

import java.time.Instant;
import java.util.Objects;

/**
 * Typed input to the sole entry-risk policy.
 *
 * The correlation ID is created before any trade or order row exists and is
 * persisted with every risk check so the later execution record can be traced
 * back to the exact accepted intent.
 */
public record EntryRiskRequest(
        EntryIntent intent,
        ExecutionMode mode,
        String correlationId
) {
    public EntryRiskRequest {
        Objects.requireNonNull(intent, "intent is required");
        Objects.requireNonNull(mode, "mode is required");
        if (intent.side() != TradeSide.BUY) {
            throw new IllegalArgumentException("EntryRiskRequest requires BUY side");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is required");
        }
    }

    public static EntryRiskRequest of(EntryIntent intent, ExecutionMode mode) {
        return new EntryRiskRequest(intent, mode, correlationId(intent.tradeIntent(), mode));
    }

    TradeIntent tradeIntent() {
        return intent.tradeIntent();
    }

    StrategyInstanceKey owner() {
        return intent.owner();
    }

    boolean matches(TradeIntent candidate, ExecutionMode candidateMode) {
        return candidateMode == mode && Objects.equals(candidate, tradeIntent());
    }

    private static String correlationId(TradeIntent intent, ExecutionMode mode) {
        Instant decisionAt = intent.decisionAt() == null ? Instant.EPOCH : intent.decisionAt();
        return "ENTRY:"
                + mode + ":"
                + (intent.botId() == null ? "default" : intent.botId()) + ":"
                + intent.strategyId() + ":"
                + intent.marketId() + ":"
                + intent.tokenId() + ":"
                + decisionAt.toEpochMilli();
    }
}
