package com.vokerg.voktrader.marketdata;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshotEntity, Long> {
    List<PriceSnapshotEntity> findByMarketIdInOrderByMarketIdAscCapturedAtAsc(Collection<Long> marketIds);
}
