package com.vokerg.voktrader.market;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface MarketRepository extends JpaRepository<MarketEntity, Long>, JpaSpecificationExecutor<MarketEntity> {

    Optional<MarketEntity> findByPolymarketMarketId(String polymarketMarketId);
    List<MarketEntity> findAllByOrderByLastSeenAtDesc(Pageable pageable);

    List<MarketEntity> findTop10ByEndDateBeforeAndResolutionStatusOrderByEndDateAsc(
            Instant endDate,
            MarketResolutionStatus resolutionStatus
    );

    @Query("""
            select m from MarketEntity m
            where m.resolutionStatus = com.vokerg.voktrader.market.MarketResolutionStatus.RESOLVED
              and m.winningOutcome is not null
              and m.polymarketMarketId in :marketIds
            """)
    List<MarketEntity> findResolvedWithWinningOutcomeByPolymarketMarketIdIn(List<String> marketIds);
}
