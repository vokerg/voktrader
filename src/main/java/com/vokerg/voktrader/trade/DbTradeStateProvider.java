package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class DbTradeStateProvider implements TradeStateProvider {
    private static final List<TradeStatus> ACTIVE_STATUSES = List.of(
            TradeStatus.ENTRY_PENDING,
            TradeStatus.PARTIALLY_OPEN,
            TradeStatus.OPEN,
            TradeStatus.EXIT_PENDING,
            TradeStatus.PARTIALLY_CLOSED
    );

    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;

    public DbTradeStateProvider(TradeRepository tradeRepository, TradeOrderRepository tradeOrderRepository) {
        this.tradeRepository = tradeRepository;
        this.tradeOrderRepository = tradeOrderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public StrategyRuntimeState getState(StrategyInstanceKey strategyInstanceKey, String marketId) {
        if (strategyInstanceKey == null || marketId == null || marketId.isBlank()) {
            return StrategyRuntimeState.empty(strategyInstanceKey, marketId);
        }
        Optional<TradeEntity> trade = strategyInstanceKey.botId() == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                        strategyInstanceKey.strategyId(),
                        marketId,
                        ACTIVE_STATUSES
                )
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                        strategyInstanceKey.botId(),
                        strategyInstanceKey.strategyId(),
                        marketId,
                        ACTIVE_STATUSES
                );
        return trade.map(entity -> fromTrade(strategyInstanceKey, entity))
                .orElseGet(() -> StrategyRuntimeState.empty(strategyInstanceKey, marketId));
    }

    private StrategyRuntimeState fromTrade(StrategyInstanceKey key, TradeEntity trade) {
        List<TradeOrderEntity> orders = trade.getId() == null ? List.of() : tradeOrderRepository.findByTradeId(trade.getId());
        OrderRuntimeState entryOrder = latestOrder(orders, TradeOrderPhase.ENTRY);
        OrderRuntimeState exitOrder = latestOrder(orders, TradeOrderPhase.EXIT);
        OrderRuntimeState feeSource = exitOrder == null ? entryOrder : exitOrder;
        BigDecimal realizedFee = firstNonNull(trade.getTotalFeeUsd(), trade.getEntryFeeUsd(), feeSource == null ? null : feeSource.realizedFeeUsd());
        boolean feeKnown = realizedFee != null || (feeSource != null && Boolean.TRUE.equals(feeSource.feeKnown()));
        LiquidityRole role = feeSource == null ? null : feeSource.fillRole();
        String lastFailure = firstNonBlank(
                exitOrder == null ? null : exitOrder.lastFailureReason(),
                entryOrder == null ? null : entryOrder.lastFailureReason()
        );
        Instant lastReconciled = latestInstant(
                entryOrder == null ? null : entryOrder.lastReconciledAt(),
                exitOrder == null ? null : exitOrder.lastReconciledAt()
        );
        return new StrategyRuntimeState(
                key,
                trade.getMarketId(),
                trade.getStrategyId(),
                trade.getTokenId(),
                trade.getStatus() == null ? TradeStatus.UNKNOWN : trade.getStatus(),
                entryOrder != null && entryOrder.isActive() ? entryOrder : null,
                exitOrder != null && exitOrder.isActive() ? exitOrder : null,
                firstNonNull(trade.getEntryFilledShares(), entryOrder == null ? null : entryOrder.filledShares()),
                entryOrder == null ? null : entryOrder.remainingShares(),
                firstNonNull(trade.getEntryAvgPrice(), entryOrder == null ? null : entryOrder.avgFillPrice()),
                realizedFee,
                feeKnown,
                role,
                null,
                null,
                lastFailure,
                trade.getUpdatedAt(),
                lastReconciled
        );
    }

    private OrderRuntimeState latestOrder(List<TradeOrderEntity> orders, TradeOrderPhase phase) {
        return orders.stream()
                .filter(order -> order.getPhase() == phase)
                .max(Comparator.comparing(this::orderUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(OrderRuntimeState::from)
                .orElse(null);
    }

    private Instant orderUpdatedAt(TradeOrderEntity order) {
        return firstNonNull(order.getUpdatedAt(), order.getSubmittedAt(), order.getCreatedAt());
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant latestInstant(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
