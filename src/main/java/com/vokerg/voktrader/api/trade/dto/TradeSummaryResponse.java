package com.vokerg.voktrader.api.trade.dto;

import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeSide;
import com.vokerg.voktrader.trade.TradeStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeSummaryResponse(
        Long id,
        Long botId,
        ExecutionMode mode,
        String strategyId,
        String marketId,
        String marketSlug,
        String question,
        String tokenId,
        String outcome,
        TradeStatus status,
        TradeSide decisionSide,
        BigDecimal intendedAmountUsd,
        BigDecimal entryFilledUsd,
        BigDecimal exitFilledUsd,
        BigDecimal finalPnlUsd,
        Instant decisionAt,
        Instant updatedAt
) {
    public static TradeSummaryResponse from(TradeEntity entity) {
        return new TradeSummaryResponse(
                entity.getId(),
                entity.getBotId(),
                entity.getMode(),
                entity.getStrategyId(),
                entity.getMarketId(),
                entity.getMarketSlug(),
                entity.getQuestion(),
                entity.getTokenId(),
                entity.getOutcome(),
                entity.getStatus(),
                entity.getDecisionSide(),
                entity.getIntendedAmountUsd(),
                entity.getEntryFilledUsd(),
                entity.getExitFilledUsd(),
                entity.getFinalPnlUsd(),
                entity.getDecisionAt(),
                entity.getUpdatedAt()
        );
    }
}
