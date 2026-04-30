package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {
    boolean existsByClientOrderId(String clientOrderId);

    Optional<TradeOrderEntity> findByClientOrderId(String clientOrderId);

    List<TradeOrderEntity> findByTradeId(Long tradeId);
}
