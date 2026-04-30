package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    List<TradeEntity> findByStrategyIdAndStatus(String strategyId, TradeStatus status);

    Optional<TradeEntity> findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
            String strategyId,
            String marketId,
            String tokenId,
            TradeStatus status
    );

    Optional<TradeEntity> findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
            String strategyId,
            String marketId,
            TradeStatus status
    );
}
