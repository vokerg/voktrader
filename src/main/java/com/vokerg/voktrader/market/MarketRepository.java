package com.vokerg.voktrader.market;


import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface MarketRepository extends JpaRepository<MarketEntity, Long> {

    Optional<MarketEntity> findByPolymarketMarketId(String polymarketMarketId);

    List<MarketEntity> findTop10ByEndDateBeforeAndResolutionStatusOrderByEndDateAsc(
            Instant endDate,
            MarketResolutionStatus resolutionStatus
    );
}
