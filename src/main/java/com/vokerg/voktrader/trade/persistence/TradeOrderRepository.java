package com.vokerg.voktrader.trade.persistence;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeVenue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long>, JpaSpecificationExecutor<TradeOrderEntity> {
    boolean existsByClientOrderId(String clientOrderId);

    Optional<TradeOrderEntity> findByClientOrderId(String clientOrderId);

    Optional<TradeOrderEntity> findByRemoteOrderId(String remoteOrderId);

    Optional<TradeOrderEntity> findByLocalOrderId(String localOrderId);

    List<TradeOrderEntity> findByTradeId(Long tradeId);

    List<TradeOrderEntity> findByTradeIdOrderByIdAsc(Long tradeId);

    List<TradeOrderEntity> findByStatusIn(List<TradeOrderStatus> statuses);

    List<TradeOrderEntity> findByStatusInAndRemoteOrderIdIsNotNull(List<TradeOrderStatus> statuses);

    List<TradeOrderEntity> findByModeAndVenueAndStatusInOrderByUpdatedAtAsc(
            ExecutionMode mode,
            TradeVenue venue,
            List<TradeOrderStatus> statuses
    );

    @Query("""
            select o
            from TradeOrderEntity o
            join TradeEntity t on t.id = o.tradeId
            where o.remoteOrderId is not null
              and o.reconciliationPausedAt is null
              and (o.nextReconcileAt is null or o.nextReconcileAt <= :now)
              and (
                    o.status in :activeStatuses
                    or (
                        o.status = com.vokerg.voktrader.trade.model.TradeOrderStatus.EXPIRED
                        and o.phase = com.vokerg.voktrader.trade.model.TradeOrderPhase.ENTRY
                        and o.filledShares is null
                        and t.status = com.vokerg.voktrader.trade.model.TradeStatus.CANCELLED
                    )
              )
            order by case when o.nextReconcileAt is null then 0 else 1 end asc,
                     o.nextReconcileAt asc,
                     o.updatedAt asc
            """)
    List<TradeOrderEntity> findReconcilableRemoteOrders(
            @Param("activeStatuses") List<TradeOrderStatus> activeStatuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
            select count(o) > 0
            from TradeOrderEntity o
            where (:botId is null or o.botId = :botId)
              and o.strategyId = :strategyId
              and o.marketId = :marketId
              and o.tokenId = :tokenId
              and o.side = :side
              and o.phase = :phase
              and o.mode = :mode
              and o.status in :statuses
              and o.createdAt >= :createdAt
            """)
    boolean existsRecentOrder(
            @Param("botId") Long botId,
            @Param("strategyId") String strategyId,
            @Param("marketId") String marketId,
            @Param("tokenId") String tokenId,
            @Param("side") TradeSide side,
            @Param("phase") TradeOrderPhase phase,
            @Param("mode") ExecutionMode mode,
            @Param("statuses") List<TradeOrderStatus> statuses,
            @Param("createdAt") Instant createdAt
    );

    @Query("""
            select distinct entry.tradeId
            from TradeOrderEntity entry
            where entry.phase = com.vokerg.voktrader.trade.model.TradeOrderPhase.ENTRY
              and (
                    entry.mode = com.vokerg.voktrader.trade.model.ExecutionMode.LIVE
                    or entry.venue = com.vokerg.voktrader.trade.model.TradeVenue.POLYMARKET
                    or entry.remoteOrderId is not null
                    or entry.exchangeOrderId is not null
              )
              and exists (
                    select 1
                    from TradeOrderEntity exit
                    where exit.tradeId = entry.tradeId
                      and exit.phase = com.vokerg.voktrader.trade.model.TradeOrderPhase.EXIT
                      and exit.venue = com.vokerg.voktrader.trade.model.TradeVenue.PAPER_SIM
              )
            """)
    List<Long> findTradeIdsWithLiveEntryAndPaperExit();
}
