package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.economy.LiquidityRole;

import java.math.BigDecimal;
import java.time.Instant;

public record StrategyRuntimeState(
        StrategyInstanceKey strategyInstanceKey,
        String marketId,
        String strategyId,
        String tokenId,
        TradeStatus currentTradeStatus,
        OrderRuntimeState activeEntryOrder,
        OrderRuntimeState activeExitOrder,
        BigDecimal filledShares,
        BigDecimal remainingShares,
        BigDecimal avgEntryPrice,
        BigDecimal realizedFeeUsd,
        boolean feeKnown,
        LiquidityRole fillRole,
        BigDecimal unrealizedPnlUsd,
        BigDecimal unrealizedPnlPct,
        String lastOrderFailureReason,
        Instant lastUpdatedAt,
        Instant lastReconciledAt
) {
    public static StrategyRuntimeState empty(StrategyInstanceKey key, String marketId) {
        return new StrategyRuntimeState(
                key,
                marketId,
                key == null ? null : key.strategyId(),
                null,
                TradeStatus.NEW,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public boolean hasActiveTrade() {
        return currentTradeStatus != null && currentTradeStatus.isActive();
    }

    public boolean hasPosition() {
        return currentTradeStatus != null && currentTradeStatus.hasPosition();
    }

    public boolean pendingEntry() {
        return currentTradeStatus != null && currentTradeStatus.isPendingEntry();
    }

    public boolean pendingExit() {
        return currentTradeStatus != null && currentTradeStatus.isPendingExit();
    }
}
