package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutorOrderCommand(
        String idempotencyKey,
        String strategyId,
        String ruleId,
        String marketId,
        String marketSlug,
        String question,
        String conditionId,
        String tokenId,
        String outcome,
        TradeSide side,
        BigDecimal amountUsd,
        BigDecimal shares,
        BigDecimal limitPrice,
        String timeInForce,
        boolean postOnly,
        boolean dryRun,
        Instant decisionAt
) {
    public static ExecutorOrderCommand fromIntent(TradeIntent intent, String idempotencyKey, boolean dryRun) {
        return new ExecutorOrderCommand(
                idempotencyKey,
                intent.strategyId(),
                intent.ruleId(),
                intent.marketId(),
                intent.marketSlug(),
                intent.question(),
                intent.conditionId(),
                intent.tokenId(),
                intent.outcome(),
                intent.side(),
                intent.amountUsd(),
                intent.shares(),
                intent.expectedPrice(),
                intent.orderType().name(),
                intent.postOnly(),
                dryRun,
                intent.decisionAt()
        );
    }
}
