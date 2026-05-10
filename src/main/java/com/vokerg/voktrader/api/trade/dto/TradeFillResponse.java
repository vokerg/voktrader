package com.vokerg.voktrader.api.trade.dto;

import com.vokerg.voktrader.trade.TradeFillEntity;
import com.vokerg.voktrader.trade.TradeSide;
import com.vokerg.voktrader.trade.TradeVenue;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeFillResponse(
        Long id,
        Long tradeId,
        Long orderId,
        String exchangeOrderId,
        String remoteFillId,
        String marketId,
        String tokenId,
        TradeVenue venue,
        TradeSide side,
        BigDecimal price,
        BigDecimal shares,
        BigDecimal amountUsd,
        BigDecimal feeUsd,
        Boolean feeKnown,
        String liquidityRole,
        Instant filledAt,
        Instant occurredAt,
        Instant receivedAt,
        Instant createdAt
) {
    public static TradeFillResponse from(TradeFillEntity entity) {
        return new TradeFillResponse(
                entity.getId(),
                entity.getTradeId(),
                entity.getOrderId(),
                entity.getExchangeOrderId(),
                entity.getRemoteFillId(),
                entity.getMarketId(),
                entity.getTokenId(),
                entity.getVenue(),
                entity.getSide(),
                entity.getPrice(),
                entity.getShares(),
                entity.getAmountUsd(),
                entity.getFeeUsd(),
                entity.getFeeKnown(),
                entity.getLiquidityRole(),
                entity.getFilledAt(),
                entity.getOccurredAt(),
                entity.getReceivedAt(),
                entity.getCreatedAt()
        );
    }
}
