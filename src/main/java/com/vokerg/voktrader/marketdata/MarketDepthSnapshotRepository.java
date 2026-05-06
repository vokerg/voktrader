package com.vokerg.voktrader.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MarketDepthSnapshotRepository extends JpaRepository<MarketDepthSnapshotEntity, Long> {
    List<MarketDepthSnapshotEntity> findByMarketIdAndCapturedAt(Long marketId, Instant capturedAt);
}
