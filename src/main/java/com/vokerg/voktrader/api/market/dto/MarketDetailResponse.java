package com.vokerg.voktrader.api.market.dto;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.market.MarketResolutionStatus;
import com.vokerg.voktrader.market.MarketTrackingStatus;

import java.time.Instant;
import java.util.List;

public record MarketDetailResponse(
        Long id,
        String polymarketMarketId,
        String conditionId,
        String question,
        String slug,
        Instant endDate,
        boolean active,
        boolean closed,
        boolean acceptingOrders,
        boolean resolved,
        MarketTrackingStatus trackingStatus,
        MarketResolutionStatus resolutionStatus,
        String winningOutcome,
        String winningAssetId,
        Instant resolvedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        MarketPriceSnapshotResponse latestPrice,
        MarketOrderBookSnapshotResponse latestOrderBook,
        List<MarketPriceSnapshotResponse> recentPrices,
        List<MarketOrderBookSnapshotResponse> recentOrderBooks
) {
    public static MarketDetailResponse from(
            MarketEntity entity,
            MarketPriceSnapshotResponse latestPrice,
            MarketOrderBookSnapshotResponse latestOrderBook,
            List<MarketPriceSnapshotResponse> recentPrices,
            List<MarketOrderBookSnapshotResponse> recentOrderBooks
    ) {
        return new MarketDetailResponse(
                entity.getId(),
                entity.getPolymarketMarketId(),
                entity.getConditionId(),
                entity.getQuestion(),
                entity.getSlug(),
                entity.getEndDate(),
                entity.isActive(),
                entity.isClosed(),
                entity.isAcceptingOrders(),
                entity.isResolved(),
                entity.getTrackingStatus(),
                entity.getResolutionStatus(),
                entity.getWinningOutcome(),
                entity.getWinningAssetId(),
                entity.getResolvedAt(),
                entity.getFirstSeenAt(),
                entity.getLastSeenAt(),
                latestPrice,
                latestOrderBook,
                recentPrices,
                recentOrderBooks
        );
    }
}
