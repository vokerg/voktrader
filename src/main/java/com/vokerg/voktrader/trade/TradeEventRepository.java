package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeEventRepository extends JpaRepository<TradeEventEntity, Long> {
    List<TradeEventEntity> findByTradeIdOrderByCreatedAtAsc(Long tradeId);
}
