package com.vokerg.voktrader.api.trade.dto;

import com.vokerg.voktrader.trade.TradeEventEntity;

import java.time.Instant;

public record TradeEventResponse(
        Long id,
        Long tradeId,
        Long tradeOrderId,
        Long tradeFillId,
        String eventType,
        String message,
        String payloadJson,
        Instant createdAt
) {
    public static TradeEventResponse from(TradeEventEntity entity) {
        return new TradeEventResponse(
                entity.getId(),
                entity.getTradeId(),
                entity.getTradeOrderId(),
                entity.getTradeFillId(),
                entity.getEventType(),
                entity.getMessage(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
