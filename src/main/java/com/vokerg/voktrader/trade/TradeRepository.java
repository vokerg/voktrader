package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    boolean existsByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
            String marketId,
            String tokenId,
            String strategyId,
            Collection<TradeStatus> statuses
    );

    long countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
            String marketId,
            String tokenId,
            String strategyId,
            Collection<TradeStatus> statuses
    );

    long countByMarketIdAndStrategyId(String marketId, String strategyId);

    long countByModeInAndStatusIn(Collection<ExecutionMode> modes, Collection<TradeStatus> statuses);

    List<TradeEntity> findByMarketIdAndStatus(String marketId, TradeStatus status);

    List<TradeEntity> findByStatus(TradeStatus status);
}
