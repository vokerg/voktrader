package com.vokerg.voktrader.trade.persistence;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<TradeEntity, Long>, JpaSpecificationExecutor<TradeEntity> {
    boolean existsByMarketIdAndTokenIdAndStrategyIdAndStatusIn(String marketId, String tokenId, String strategyId, Collection<TradeStatus> statuses);
    long countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(String marketId, String tokenId, String strategyId, Collection<TradeStatus> statuses);
    long countByMarketIdAndStrategyIdAndStatusIn(String marketId, String strategyId, Collection<TradeStatus> statuses);
    long countByModeInAndStatusIn(Collection<ExecutionMode> modes, Collection<TradeStatus> statuses);
    @Query("""
            select count(t)
            from TradeEntity t
            where t.mode in :modes
              and t.status in :statuses
              and (t.marketEndAt is null or t.marketEndAt > :now)
            """)
    long countLiveCapacityTrades(
            @Param("modes") Collection<ExecutionMode> modes,
            @Param("statuses") Collection<TradeStatus> statuses,
            @Param("now") Instant now
    );
    long countByStrategyIdAndMarketId(String strategyId, String marketId);
    List<TradeEntity> findByMarketIdAndStatus(String marketId, TradeStatus status);
    List<TradeEntity> findByStatus(TradeStatus status);
    @Query("select distinct t.marketId from TradeEntity t where t.status = :status")
    List<String> findDistinctMarketIdByStatus(TradeStatus status);
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
    List<TradeEntity> findByBacktestRunIdAndStrategyIdAndStatus(String backtestRunId, String strategyId, TradeStatus status);
    long countByBacktestRunIdAndStrategyIdAndMarketId(String backtestRunId, String strategyId, String marketId);
    Optional<TradeEntity> findFirstByBacktestRunIdAndStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(String backtestRunId, String strategyId, String marketId, TradeStatus status);
    Optional<TradeEntity> findFirstByBacktestRunIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(String backtestRunId, String strategyId, String marketId, String tokenId, TradeStatus status);
    Optional<TradeEntity> findFirstByBacktestRunIdAndStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(String backtestRunId, String strategyId, String marketId, Collection<TradeStatus> statuses);
    Optional<TradeEntity> findFirstByBacktestRunIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(String backtestRunId, String strategyId, String marketId, String tokenId, Collection<TradeStatus> statuses);
    List<TradeEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
