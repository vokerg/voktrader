package com.vokerg.voktrader.api.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.vokerg.voktrader.marketdata.model.PriceSnapshotEntity;

public record MarketPriceSnapshotResponse(
        Long marketId,
        Long botId,
        Long remainingSeconds,
        BigDecimal upBid,
        BigDecimal upAsk,
        BigDecimal upSpread,
        BigDecimal downBid,
        BigDecimal downAsk,
        BigDecimal downSpread,
        Instant capturedAt
) {
    public static MarketPriceSnapshotResponse from(PriceSnapshotEntity entity) {
        return new MarketPriceSnapshotResponse(
                entity.getMarketId(),
                entity.getBotId(),
                entity.getRemainingSeconds(),
                entity.getUpBid(),
                entity.getUpAsk(),
                entity.getUpSpread(),
                entity.getDownBid(),
                entity.getDownAsk(),
                entity.getDownSpread(),
                entity.getCapturedAt()
        );
    }
}
