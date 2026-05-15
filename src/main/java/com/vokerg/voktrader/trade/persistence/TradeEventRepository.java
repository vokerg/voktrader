package com.vokerg.voktrader.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vokerg.voktrader.trade.model.TradeEventEntity;

import java.util.List;

public interface TradeEventRepository extends JpaRepository<TradeEventEntity, Long> {
    List<TradeEventEntity> findByTradeIdOrderByCreatedAtAsc(Long tradeId);

    boolean existsByTradeOrderIdAndEventType(Long tradeOrderId, String eventType);
}
