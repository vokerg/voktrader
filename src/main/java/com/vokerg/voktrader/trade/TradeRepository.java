package com.vokerg.voktrader.trade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    boolean existsByMarketIdAndTokenIdAndStrategyIdAndStatusIn(String marketId, String tokenId, String strategyId, Collection<TradeStatus> statuses);
    long countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(String marketId, String tokenId, String strategyId, Collection<TradeStatus> statuses);
    long countByMarketIdAndStrategyIdAndStatusIn(String marketId, String strategyId, Collection<TradeStatus> statuses);
    long countByModeInAndStatusIn(Collection<ExecutionMode> modes, Collection<TradeStatus> statuses);
    long countByStrategyIdAndMarketId(String strategyId, String marketId);
    List<TradeEntity> findByMarketIdAndStatus(String marketId, TradeStatus status);
    List<TradeEntity> findByStatus(TradeStatus status);
    List<TradeEntity> findByStrategyIdAndStatus(String strategyId, TradeStatus status);
    Optional<TradeEntity> findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(String strategyId, String marketId, String tokenId, TradeStatus status);
    Optional<TradeEntity> findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(String strategyId, String marketId, TradeStatus status);
    Optional<TradeEntity> findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(String strategyId, String marketId, Collection<TradeStatus> statuses);
    Optional<TradeEntity> findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(String strategyId, String marketId, String tokenId, Collection<TradeStatus> statuses);

    long countByBotIdAndMarketIdAndTokenIdAndStrategyIdAndStatusIn(Long botId, String marketId, String tokenId, String strategyId, Collection<TradeStatus> statuses);
    long countByBotIdAndMarketIdAndStrategyIdAndStatusIn(Long botId, String marketId, String strategyId, Collection<TradeStatus> statuses);
    long countByBotIdAndStrategyIdAndMarketId(Long botId, String strategyId, String marketId);
    Optional<TradeEntity> findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(Long botId, String strategyId, String marketId, String tokenId, TradeStatus status);
    Optional<TradeEntity> findFirstByBotIdAndStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(Long botId, String strategyId, String marketId, TradeStatus status);
    Optional<TradeEntity> findFirstByBotIdAndStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(Long botId, String strategyId, String marketId, Collection<TradeStatus> statuses);
    Optional<TradeEntity> findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(Long botId, String strategyId, String marketId, String tokenId, Collection<TradeStatus> statuses);

    List<TradeEntity> findByBacktestRunId(String backtestRunId);
}
