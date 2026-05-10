package com.vokerg.voktrader.trade.persistence;

import com.vokerg.voktrader.trade.TradeFillEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeFillRepository extends JpaRepository<TradeFillEntity, Long> {
    List<TradeFillEntity> findByTradeId(Long tradeId);
    List<TradeFillEntity> findByTradeIdInOrderByTradeIdAscFilledAtAsc(List<Long> tradeIds);

    List<TradeFillEntity> findByOrderId(Long orderId);

    Optional<TradeFillEntity> findByRemoteFillId(String remoteFillId);
}
