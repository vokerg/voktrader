package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRiskCheckRepository extends JpaRepository<TradeRiskCheckEntity, Long> {
    List<TradeRiskCheckEntity> findByTradeId(Long tradeId);
}
