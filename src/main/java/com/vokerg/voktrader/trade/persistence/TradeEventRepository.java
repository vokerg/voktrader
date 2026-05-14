package com.vokerg.voktrader.trade.persistence;

import com.vokerg.voktrader.trade.TradeEventEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeEventRepository extends JpaRepository<TradeEventEntity, Long> {
    List<TradeEventEntity> findByTradeIdOrderByCreatedAtAsc(Long tradeId);

    boolean existsByTradeOrderIdAndEventType(Long tradeOrderId, String eventType);
}
