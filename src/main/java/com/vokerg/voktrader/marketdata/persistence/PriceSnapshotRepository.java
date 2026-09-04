package com.vokerg.voktrader.marketdata.persistence;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.marketdata.model.PriceSnapshotEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshotEntity, Long> {
    List<PriceSnapshotEntity> findByMarketIdInOrderByMarketIdAscCapturedAtAsc(Collection<Long> marketIds);
    Optional<PriceSnapshotEntity> findFirstByMarketOrderByCapturedAtDesc(MarketEntity market);
    List<PriceSnapshotEntity> findByMarket(MarketEntity market, Pageable pageable);

    @Query("""
            select snapshot.marketId as marketId,
                   market.id as marketEntityId,
                   min(snapshot.capturedAt) as intervalStartAt,
                   max(snapshot.capturedAt) as intervalEndAt
            from PriceSnapshotEntity snapshot
            left join snapshot.market market
            where snapshot.capturedAt is not null
            group by snapshot.marketId, market.id
            order by min(snapshot.capturedAt), snapshot.marketId, market.id
            """)
    List<HistoricalTickIntervalProjection> inventoryHistoricalTickIntervals();

    interface HistoricalTickIntervalProjection {
        Long getMarketId();
        Long getMarketEntityId();
        Instant getIntervalStartAt();
        Instant getIntervalEndAt();
    }
}
