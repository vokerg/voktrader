package com.vokerg.voktrader.marketdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotLevelEntity;

import java.time.Instant;
import java.util.List;

public interface MarketDepthSnapshotLevelRepository extends JpaRepository<MarketDepthSnapshotLevelEntity, Long> {
    List<MarketDepthSnapshotLevelEntity> findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(
            Long marketId,
            Instant capturedAt
    );

    @Query("""
            select level.marketId as marketId,
                   level.tokenId as tokenId,
                   min(level.capturedAt) as intervalStartAt,
                   max(level.capturedAt) as intervalEndAt
            from MarketDepthSnapshotLevelEntity level
            where level.tokenId is not null
              and level.capturedAt is not null
            group by level.marketId, level.tokenId
            order by min(level.capturedAt), level.marketId, level.tokenId
            """)
    List<HistoricalTickIntervalProjection> inventoryHistoricalTickIntervals();

    interface HistoricalTickIntervalProjection {
        Long getMarketId();
        String getTokenId();
        Instant getIntervalStartAt();
        Instant getIntervalEndAt();
    }
}
