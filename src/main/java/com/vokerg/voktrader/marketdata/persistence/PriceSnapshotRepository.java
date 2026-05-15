package com.vokerg.voktrader.marketdata.persistence;


import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.marketdata.model.PriceSnapshotEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshotEntity, Long> {
    List<PriceSnapshotEntity> findByMarketIdInOrderByMarketIdAscCapturedAtAsc(Collection<Long> marketIds);
    Optional<PriceSnapshotEntity> findFirstByMarketOrderByCapturedAtDesc(MarketEntity market);
    List<PriceSnapshotEntity> findByMarket(MarketEntity market, Pageable pageable);
}
