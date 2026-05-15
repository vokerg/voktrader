package com.vokerg.voktrader.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vokerg.voktrader.trade.model.TradeRiskCheckEntity;

import java.util.List;

public interface TradeRiskCheckRepository extends JpaRepository<TradeRiskCheckEntity, Long> {
    List<TradeRiskCheckEntity> findByTradeId(Long tradeId);
}
