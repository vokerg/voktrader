package com.vokerg.voktrader.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vokerg.voktrader.trade.model.TradeFillEntity;

import java.util.List;
import java.util.Optional;

public interface TradeFillRepository extends JpaRepository<TradeFillEntity, Long> {
    List<TradeFillEntity> findByTradeId(Long tradeId);
    List<TradeFillEntity> findByTradeIdOrderByIdAsc(Long tradeId);
    List<TradeFillEntity> findByTradeIdInOrderByTradeIdAscFilledAtAsc(List<Long> tradeIds);

    List<TradeFillEntity> findByOrderId(Long orderId);
    List<TradeFillEntity> findByOrderIdOrderByIdAsc(Long orderId);

    Optional<TradeFillEntity> findByRemoteFillId(String remoteFillId);

    Optional<TradeFillEntity> findByRemoteFillKey(String remoteFillKey);
}
