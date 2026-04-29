package com.vokerg.voktrader.market;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface MarketRepository extends JpaRepository<MarketEntity, Long> {

    Optional<MarketEntity> findByPolymarketMarketId(String polymarketMarketId);
}
