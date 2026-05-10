package com.vokerg.voktrader.api.market.dto;

import com.vokerg.voktrader.marketdata.MarketDepthSnapshotEntity;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketOrderBookSnapshotResponse(
        Long marketId,
        String outcome,
        String tokenId,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal spread,
        BigDecimal bidDepth,
        BigDecimal askDepth,
        BigDecimal nearBidDepth,
        BigDecimal nearAskDepth,
        BigDecimal depthImbalance,
        BigDecimal nearDepthImbalance,
        BigDecimal estimateBuyUsd,
        BigDecimal estimateBuyFilledShares,
        BigDecimal estimateBuyAveragePrice,
        BigDecimal estimateBuyWorstPrice,
        Boolean estimateBuyComplete,
        Integer estimateBuyLevelsConsumed,
        Long bookAgeMs,
        Boolean stale,
        Instant bookUpdatedAt,
        Instant capturedAt
) {
    public static MarketOrderBookSnapshotResponse from(MarketDepthSnapshotEntity entity) {
        return new MarketOrderBookSnapshotResponse(
                entity.getMarketId(),
                entity.getOutcome(),
                entity.getTokenId(),
                entity.getBestBid(),
                entity.getBestAsk(),
                entity.getSpread(),
                entity.getBidDepth(),
                entity.getAskDepth(),
                entity.getNearBidDepth(),
                entity.getNearAskDepth(),
                entity.getDepthImbalance(),
                entity.getNearDepthImbalance(),
                entity.getEstimateBuyUsd(),
                entity.getEstimateBuyFilledShares(),
                entity.getEstimateBuyAveragePrice(),
                entity.getEstimateBuyWorstPrice(),
                entity.getEstimateBuyComplete(),
                entity.getEstimateBuyLevelsConsumed(),
                entity.getBookAgeMs(),
                entity.getStale(),
                entity.getBookUpdatedAt(),
                entity.getCapturedAt()
        );
    }
}
