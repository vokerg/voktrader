package com.vokerg.voktrader.marketdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotLevelEntity;

import java.time.Instant;
import java.util.List;

public interface MarketDepthSnapshotLevelRepository extends JpaRepository<MarketDepthSnapshotLevelEntity, Long> {
    List<MarketDepthSnapshotLevelEntity> findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(
            Long marketId,
            Instant capturedAt
    );
}
