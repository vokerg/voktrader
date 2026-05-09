package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {
    boolean existsByClientOrderId(String clientOrderId);

    Optional<TradeOrderEntity> findByClientOrderId(String clientOrderId);

    Optional<TradeOrderEntity> findByRemoteOrderId(String remoteOrderId);

    Optional<TradeOrderEntity> findByLocalOrderId(String localOrderId);

    List<TradeOrderEntity> findByTradeId(Long tradeId);

    List<TradeOrderEntity> findByStatusIn(List<TradeOrderStatus> statuses);
}
