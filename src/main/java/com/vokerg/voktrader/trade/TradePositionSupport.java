package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeStatus;

import java.math.BigDecimal;
import java.util.List;

public final class TradePositionSupport {
    public static final List<TradeStatus> EXITABLE_STATUSES = List.of(
            TradeStatus.OPEN,
            TradeStatus.PARTIALLY_OPEN,
            TradeStatus.PARTIALLY_CLOSED
    );

    private TradePositionSupport() {
    }

    public static ExitPlan planExit(TradeEntity trade, BigDecimal requestedShares) {
        BigDecimal heldShares = heldShares(trade);
        BigDecimal desiredShares = positive(requestedShares) ? requestedShares.min(heldShares) : heldShares;
        return new ExitPlan(heldShares, maxZero(desiredShares));
    }

    public static BigDecimal heldShares(TradeEntity trade) {
        if (trade == null) {
            return BigDecimal.ZERO;
        }
        return maxZero(zeroIfNull(trade.getEntryFilledShares()).subtract(zeroIfNull(trade.getExitFilledShares())));
    }

    public static BigDecimal cumulativeExitShares(TradeEntity trade, BigDecimal newExitShares) {
        return zeroIfNull(trade == null ? null : trade.getExitFilledShares()).add(zeroIfNull(newExitShares));
    }

    public static BigDecimal cumulativeExitAmountUsd(TradeEntity trade, BigDecimal newExitAmountUsd) {
        return zeroIfNull(trade == null ? null : trade.getExitFilledUsd()).add(zeroIfNull(newExitAmountUsd));
    }

    public static BigDecimal cumulativeExitFeeUsd(TradeEntity trade, BigDecimal newExitFeeUsd) {
        return zeroIfNull(trade == null ? null : trade.getExitFeeUsd()).add(zeroIfNull(newExitFeeUsd));
    }

    public static boolean closesPosition(TradeEntity trade, BigDecimal newExitShares) {
        BigDecimal entryShares = zeroIfNull(trade == null ? null : trade.getEntryFilledShares());
        return entryShares.compareTo(BigDecimal.ZERO) > 0
                && cumulativeExitShares(trade, newExitShares).compareTo(entryShares) >= 0;
    }

    public static TradeIntent withSellShares(TradeIntent intent, BigDecimal shares) {
        BigDecimal normalizedShares = maxZero(zeroIfNull(shares));
        BigDecimal amountUsd = intent.limitPrice() == null
                ? null
                : normalizedShares.multiply(intent.limitPrice());
        return new TradeIntent(
                intent.botId(),
                intent.strategyId(),
                intent.ruleId(),
                intent.marketId(),
                intent.marketSlug(),
                intent.question(),
                intent.conditionId(),
                intent.tokenId(),
                intent.outcome(),
                intent.side(),
                amountUsd,
                normalizedShares,
                intent.orderType(),
                intent.postOnly(),
                intent.limitPrice(),
                intent.observedBid(),
                intent.observedAsk(),
                intent.observedSpread(),
                intent.observedMidpoint(),
                intent.priceUpdatedAt(),
                intent.priceAgeMs(),
                intent.decisionAt(),
                intent.marketEndAt(),
                intent.secondsToExpiryAtDecision(),
                intent.reason(),
                intent.restingTtlSeconds()
        );
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal maxZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record ExitPlan(BigDecimal heldShares, BigDecimal requestedShares) {
        public boolean hasRequestedShares() {
            return requestedShares != null && requestedShares.compareTo(BigDecimal.ZERO) > 0;
        }
    }
}
