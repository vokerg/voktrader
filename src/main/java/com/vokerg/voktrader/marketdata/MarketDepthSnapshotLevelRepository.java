package com.vokerg.voktrader.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MarketDepthSnapshotLevelRepository extends JpaRepository<MarketDepthSnapshotLevelEntity, Long> {
    List<MarketDepthSnapshotLevelEntity> findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(
            Long marketId,
            Instant capturedAt
    );
}
