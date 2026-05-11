package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.market.MarketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MarketDepthSnapshotRepository extends JpaRepository<MarketDepthSnapshotEntity, Long> {
    List<MarketDepthSnapshotEntity> findByMarketIdAndCapturedAt(Long marketId, Instant capturedAt);
    List<MarketDepthSnapshotEntity> findByMarketIdInOrderByMarketIdAscCapturedAtAsc(Collection<Long> marketIds);
    Optional<MarketDepthSnapshotEntity> findFirstByMarketOrderByCapturedAtDesc(MarketEntity market);
}
