package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeFillEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderEntity;
import com.vokerg.voktrader.trade.TradeOrderPhase;
import com.vokerg.voktrader.trade.TradeOrderStatus;
import com.vokerg.voktrader.trade.TradeOrderType;
import com.vokerg.voktrader.trade.TradeSide;
import com.vokerg.voktrader.trade.TradeStateProvider;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.TradeVenue;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BacktestOrderGateway implements OrderGateway, TradeStateProvider {
    private static final int SCALE = 8;

    private final String runId;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final PolymarketFeeCalculator feeCalculator;
    private final TradingProperties tradingProperties;
    private final BacktestExecutionProperties executionProperties;
    private final Set<String> openLocalOrderIds = new LinkedHashSet<>();
    private final Map<String, Instant> expiresAtByLocalOrderId = new LinkedHashMap<>();

    public BacktestOrderGateway(
            String runId,
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            TradeFillRepository tradeFillRepository,
            PolymarketFeeCalculator feeCalculator,
            TradingProperties tradingProperties,
            BacktestExecutionProperties executionProperties
    ) {
        this.runId = runId;
        this.tradeRepository = tradeRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.tradeFillRepository = tradeFillRepository;
        this.feeCalculator = feeCalculator;
        this.tradingProperties = tradingProperties;
        this.executionProperties = executionProperties;
    }

    @Override
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        TradeEntity trade = intent.side() == TradeSide.BUY ? createEntryTrade(intent) : findExitTrade(intent).orElse(null);
        if (trade == null) {
            return new OrderLifecycleResult(false, null, null, null, null, null, null, "backtest sell rejected: no open run trade", "backtest sell rejected: no open run trade");
        }

        String localOrderId = clientOrderId(intent, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(),
                intent,
                ExecutionMode.TESTING,
                TradeVenue.BACKTEST_SIM,
                localOrderId
        ));
        order.markSubmitting(localOrderId, intent.toString());

        if (intent.orderType().expectsImmediateFill()) {
            return executeImmediate(intent, trade, order);
        }

        order.markResting("{\"backtest\":true,\"fillModel\":\"" + executionProperties.getFillModel() + "\"}");
        if (intent.orderType() == TradeOrderType.GTD) {
            expiresAtByLocalOrderId.put(localOrderId, TimeMachine.now().plusSeconds(executionProperties.getDefaultGtdSeconds()));
        }
        openLocalOrderIds.add(localOrderId);
        if (intent.side() == TradeSide.BUY) {
            trade.markEntryPending();
        } else {
            trade.markExitPending();
        }
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        return OrderLifecycleResult.of(trade, order, true, "backtest order resting");
    }

    @Override
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        TradeOrderEntity order = findOrder(localOrderId).orElse(null);
        if (order == null) {
            return new OrderLifecycleResult(false, null, null, localOrderId, null, null, null, "backtest cancel rejected: order not found", "order not found");
        }
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        order.markCancelled(reason, "{\"backtest\":true,\"cancelled\":true}");
        openLocalOrderIds.remove(order.getLocalOrderId());
        if (trade != null && order.getPhase() == TradeOrderPhase.ENTRY && zero(order.getFilledShares())) {
            trade.markCancelled();
            tradeRepository.save(trade);
        }
        tradeOrderRepository.save(order);
        return OrderLifecycleResult.of(trade, order, true, reason);
    }

    @Override
    public void advanceOpenOrders() {
        OrderBookState orderBookState = BotRuntimeContextHolder.currentOrderBookState().orElse(null);
        if (orderBookState == null || openLocalOrderIds.isEmpty()) {
            return;
        }
        for (String localOrderId : List.copyOf(openLocalOrderIds)) {
            TradeOrderEntity order = findOrder(localOrderId).orElse(null);
            if (order == null || order.getStatus() == null || !order.getStatus().isActive()) {
                openLocalOrderIds.remove(localOrderId);
                continue;
            }
            if (expired(localOrderId)) {
                expire(order);
                continue;
            }
            if (executionProperties.fillModel() == BacktestFillModel.MAKER_NO_FILL) {
                continue;
            }
            MakerFillEstimate estimate = makerFillEstimate(order, orderBookState.byTokenId(order.getTokenId()).orElse(null));
            if (estimate == null || estimate.shares().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
            fillOrder(trade, order, estimate.price(), estimate.shares(), LiquidityRole.MAKER, BigDecimal.ZERO, estimate.fullFill());
            if (estimate.fullFill()) {
                openLocalOrderIds.remove(localOrderId);
            }
        }
    }

    @Override
    public StrategyRuntimeState getState(StrategyInstanceKey owner, String marketId) {
        Optional<TradeEntity> trade = tradeRepository.findByBacktestRunId(runId).stream()
                .filter(candidate -> marketId.equals(candidate.getMarketId()))
                .filter(candidate -> owner == null || owner.strategyId().equals(candidate.getStrategyId()))
                .filter(candidate -> owner == null || owner.botId() == null || owner.botId().equals(candidate.getBotId()))
                .filter(candidate -> candidate.getStatus() != null && candidate.getStatus().isActive())
                .max(Comparator.comparing(TradeEntity::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        return trade.map(entity -> stateFromTrade(owner, entity))
                .orElseGet(() -> StrategyRuntimeState.empty(owner, marketId));
    }

    public BacktestOrderMetrics metrics() {
        List<TradeOrderEntity> orders = openAndPersistedOrders();
        long submitted = orders.size();
        long filled = orders.stream().filter(order -> order.getStatus() == TradeOrderStatus.FILLED).count();
        long partial = orders.stream().filter(order -> order.getStatus() == TradeOrderStatus.PARTIALLY_FILLED).count();
        long expired = orders.stream().filter(order -> order.getStatus() == TradeOrderStatus.EXPIRED).count();
        long cancelled = orders.stream().filter(order -> order.getStatus() == TradeOrderStatus.CANCELLED).count();
        long rejected = orders.stream().filter(order -> order.getStatus() == TradeOrderStatus.REJECTED || order.getStatus() == TradeOrderStatus.FAILED).count();
        BigDecimal fees = orders.stream()
                .map(TradeOrderEntity::getRealizedFeeUsd)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long maker = orders.stream().filter(order -> order.getFillRole() == LiquidityRole.MAKER).count();
        long taker = orders.stream().filter(order -> order.getFillRole() == LiquidityRole.TAKER).count();
        long entryPending = orders.stream().filter(order -> order.getPhase() == TradeOrderPhase.ENTRY && order.getStatus() != null && order.getStatus().isActive()).count();
        long exitPending = orders.stream().filter(order -> order.getPhase() == TradeOrderPhase.EXIT && order.getStatus() != null && order.getStatus().isActive()).count();
        return new BacktestOrderMetrics(
                submitted,
                filled,
                partial,
                expired,
                cancelled,
                rejected,
                rate(filled, submitted),
                rate(partial, submitted),
                averageRestingSeconds(orders),
                fees,
                maker,
                taker,
                entryPending,
                exitPending
        );
    }

    private OrderLifecycleResult executeImmediate(TradeIntent intent, TradeEntity trade, TradeOrderEntity order) {
        OutcomeOrderBook book = BotRuntimeContextHolder.currentOrderBookState()
                .flatMap(state -> state.byTokenId(intent.tokenId()))
                .orElse(null);
        FillEstimate estimate = immediateFill(intent, book);
        if (estimate == null || estimate.filledShares().compareTo(BigDecimal.ZERO) <= 0) {
            order.markCancelled("backtest immediate order did not fill", "{\"backtest\":true,\"filled\":false}");
            if (intent.side() == TradeSide.BUY) {
                trade.markCancelled();
            }
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            return OrderLifecycleResult.of(trade, order, false, "backtest immediate order did not fill");
        }
        if (intent.orderType() == TradeOrderType.FOK && !estimate.complete()) {
            order.markCancelled("backtest FOK insufficient depth", "{\"backtest\":true,\"filled\":false}");
            if (intent.side() == TradeSide.BUY) {
                trade.markCancelled();
            }
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            return OrderLifecycleResult.of(trade, order, false, "backtest FOK insufficient depth");
        }
        BigDecimal feeUsd = feeCalculator.estimateFeeUsd(estimate.filledShares(), estimate.averagePrice(), tradingProperties.getTakerFeeRate());
        TradeOrderStatus status = estimate.complete() ? TradeOrderStatus.FILLED : TradeOrderStatus.PARTIALLY_FILLED;
        fillOrder(trade, order, estimate.averagePrice(), estimate.filledShares(), LiquidityRole.TAKER, feeUsd, status == TradeOrderStatus.FILLED);
        if (status == TradeOrderStatus.PARTIALLY_FILLED) {
            order.applyFillState(
                    TradeOrderStatus.PARTIALLY_FILLED,
                    estimate.averagePrice(),
                    estimate.filledShares(),
                    estimate.notionalUsd(),
                    estimate.unfilledShares(),
                    feeUsd,
                    true,
                    LiquidityRole.TAKER,
                    "{\"backtest\":true,\"partial\":true}"
            );
            tradeOrderRepository.save(order);
        }
        return OrderLifecycleResult.of(trade, order, true, estimate.complete() ? "backtest immediate filled" : "backtest FAK partially filled");
    }

    private FillEstimate immediateFill(TradeIntent intent, OutcomeOrderBook book) {
        if (book == null) {
            return null;
        }
        OutcomeOrderBook executableBook = limitBook(intent, book);
        if (intent.side() == TradeSide.BUY) {
            return executableBook.estimateBuyUsd(intent.amountUsd());
        }
        BigDecimal shares = intent.shares() == null ? findExitTrade(intent).map(TradeEntity::getEntryFilledShares).orElse(null) : intent.shares();
        return executableBook.estimateSellShares(shares);
    }

    private OutcomeOrderBook limitBook(TradeIntent intent, OutcomeOrderBook book) {
        BigDecimal limit = intent.expectedPrice();
        if (limit == null) {
            return book;
        }
        if (intent.side() == TradeSide.BUY) {
            return new OutcomeOrderBook(
                    book.tokenId(),
                    book.outcome(),
                    book.bids(),
                    book.asks().stream().filter(level -> level.price().compareTo(limit) <= 0).toList(),
                    book.updatedAt()
            );
        }
        return new OutcomeOrderBook(
                book.tokenId(),
                book.outcome(),
                book.bids().stream().filter(level -> level.price().compareTo(limit) >= 0).toList(),
                book.asks(),
                book.updatedAt()
        );
    }

    private void fillOrder(
            TradeEntity trade,
            TradeOrderEntity order,
            BigDecimal price,
            BigDecimal shares,
            LiquidityRole role,
            BigDecimal feeUsd,
            boolean full
    ) {
        if (trade == null || price == null || shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal previousShares = nullToZero(order.getFilledShares());
        BigDecimal cumulativeShares = previousShares.add(shares);
        BigDecimal fillAmountUsd = price.multiply(shares).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal cumulativeAmountUsd = price.multiply(cumulativeShares).setScale(SCALE, RoundingMode.HALF_UP);
        boolean cumulativeFull = full || remaining(order, cumulativeShares).compareTo(BigDecimal.ZERO) <= 0;
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.backtest(
                trade.getId(),
                order.getId(),
                order.getSide(),
                price,
                shares,
                fillAmountUsd,
                feeUsd,
                role.name(),
                "{\"backtest\":true,\"fillModel\":\"" + executionProperties.getFillModel() + "\"}"
        ));
        order.applyFillState(
                cumulativeFull ? TradeOrderStatus.FILLED : TradeOrderStatus.PARTIALLY_FILLED,
                price,
                cumulativeShares,
                cumulativeAmountUsd,
                cumulativeFull ? BigDecimal.ZERO : remaining(order, cumulativeShares),
                feeUsd,
                true,
                role,
                fill.getRawFill()
        );
        if (order.getSide() == TradeSide.BUY) {
            if (cumulativeFull) {
                trade.markOpen(price, cumulativeShares, cumulativeAmountUsd, feeUsd, TimeMachine.now());
            } else {
                trade.markPartiallyOpen(price, cumulativeShares, cumulativeAmountUsd, feeUsd, TimeMachine.now());
            }
        } else if (cumulativeFull) {
            trade.markClosed(price, cumulativeShares, cumulativeAmountUsd, feeUsd, TimeMachine.now());
        } else {
            trade.markPartiallyClosed(price, cumulativeShares, cumulativeAmountUsd, feeUsd, TimeMachine.now());
        }
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
    }

    private MakerFillEstimate makerFillEstimate(TradeOrderEntity order, OutcomeOrderBook book) {
        if (book == null) {
            return null;
        }
        BigDecimal limit = order.getRequestedPrice();
        if (limit == null) {
            return null;
        }
        BigDecimal remainingShares = remainingShares(order);
        if (remainingShares.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BacktestFillModel fillModel = executionProperties.fillModel();
        if (order.getSide() == TradeSide.BUY) {
            OrderBookLevel bestAsk = book.bestAsk().orElse(null);
            if (bestAsk == null) {
                return null;
            }
            int comparison = bestAsk.price().compareTo(limit);
            if (comparison < 0) {
                return new MakerFillEstimate(remainingShares, limit, true, "ask crossed below buy limit");
            }
            if (comparison == 0 && fillModel == BacktestFillModel.MAKER_TOUCH) {
                return touchPartial(remainingShares, bestAsk.size(), limit, "ask touched buy limit");
            }
            return null;
        }
        OrderBookLevel bestBid = book.bestBid().orElse(null);
        if (bestBid == null) {
            return null;
        }
        int comparison = bestBid.price().compareTo(limit);
        if (comparison > 0) {
            return new MakerFillEstimate(remainingShares, limit, true, "bid crossed above sell limit");
        }
        if (comparison == 0 && fillModel == BacktestFillModel.MAKER_TOUCH) {
            return touchPartial(remainingShares, bestBid.size(), limit, "bid touched sell limit");
        }
        return null;
    }

    private MakerFillEstimate touchPartial(BigDecimal remainingShares, BigDecimal visibleDepth, BigDecimal price, String reason) {
        BigDecimal candidate = nullToZero(visibleDepth).multiply(executionProperties.makerTouchFillRatio());
        BigDecimal shares = candidate.min(remainingShares).setScale(SCALE, RoundingMode.HALF_UP);
        if (shares.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return new MakerFillEstimate(shares, price, shares.compareTo(remainingShares) >= 0, reason);
    }

    private void expire(TradeOrderEntity order) {
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        order.markExpired("{\"backtest\":true,\"expired\":true}");
        openLocalOrderIds.remove(order.getLocalOrderId());
        if (trade != null && order.getPhase() == TradeOrderPhase.ENTRY && zero(order.getFilledShares())) {
            trade.markCancelled();
            tradeRepository.save(trade);
        }
        tradeOrderRepository.save(order);
    }

    private boolean expired(String localOrderId) {
        Instant expiresAt = expiresAtByLocalOrderId.get(localOrderId);
        return expiresAt != null && !TimeMachine.now().isBefore(expiresAt);
    }

    private TradeEntity createEntryTrade(TradeIntent intent) {
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.TESTING);
        trade.attachBacktestRun(runId);
        return tradeRepository.save(trade);
    }

    private Optional<TradeEntity> findExitTrade(TradeIntent intent) {
        return tradeRepository.findByBacktestRunId(runId).stream()
                .filter(trade -> intent.marketId().equals(trade.getMarketId()))
                .filter(trade -> intent.tokenId().equals(trade.getTokenId()))
                .filter(trade -> intent.strategyId().equals(trade.getStrategyId()))
                .filter(trade -> intent.botId() == null || intent.botId().equals(trade.getBotId()))
                .filter(trade -> trade.getStatus() == TradeStatus.OPEN || trade.getStatus() == TradeStatus.PARTIALLY_OPEN)
                .max(Comparator.comparing(TradeEntity::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Optional<TradeOrderEntity> findOrder(String localOrRemoteOrderId) {
        return tradeOrderRepository.findByLocalOrderId(localOrRemoteOrderId)
                .or(() -> tradeOrderRepository.findByClientOrderId(localOrRemoteOrderId))
                .or(() -> tradeOrderRepository.findByRemoteOrderId(localOrRemoteOrderId));
    }

    private StrategyRuntimeState stateFromTrade(StrategyInstanceKey owner, TradeEntity trade) {
        List<TradeOrderEntity> orders = trade.getId() == null ? List.of() : tradeOrderRepository.findByTradeId(trade.getId());
        OrderRuntimeState entry = latest(orders, TradeOrderPhase.ENTRY);
        OrderRuntimeState exit = latest(orders, TradeOrderPhase.EXIT);
        return new StrategyRuntimeState(
                owner,
                trade.getMarketId(),
                trade.getStrategyId(),
                trade.getTokenId(),
                trade.getStatus(),
                entry != null && entry.isActive() ? entry : null,
                exit != null && exit.isActive() ? exit : null,
                trade.getEntryFilledShares(),
                entry == null ? null : entry.remainingShares(),
                trade.getEntryAvgPrice(),
                trade.getTotalFeeUsd(),
                trade.getTotalFeeUsd() != null,
                entry == null ? null : entry.fillRole(),
                null,
                null,
                null,
                trade.getUpdatedAt(),
                null
        );
    }

    private OrderRuntimeState latest(List<TradeOrderEntity> orders, TradeOrderPhase phase) {
        return orders.stream()
                .filter(order -> order.getPhase() == phase)
                .max(Comparator.comparing(TradeOrderEntity::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(OrderRuntimeState::from)
                .orElse(null);
    }

    private List<TradeOrderEntity> openAndPersistedOrders() {
        return tradeRepository.findByBacktestRunId(runId).stream()
                .flatMap(trade -> tradeOrderRepository.findByTradeId(trade.getId()).stream())
                .toList();
    }

    private BigDecimal requestedShares(TradeOrderEntity order) {
        if (order.getRequestedShares() != null) {
            return order.getRequestedShares();
        }
        if (order.getRequestedAmountUsd() != null && order.getRequestedPrice() != null && order.getRequestedPrice().compareTo(BigDecimal.ZERO) > 0) {
            return order.getRequestedAmountUsd().divide(order.getRequestedPrice(), SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal remaining(TradeOrderEntity order, BigDecimal filledShares) {
        BigDecimal requested = requestedShares(order);
        BigDecimal remaining = requested.subtract(filledShares == null ? BigDecimal.ZERO : filledShares);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private BigDecimal remainingShares(TradeOrderEntity order) {
        if (order.getRemainingShares() != null) {
            return order.getRemainingShares();
        }
        return remaining(order, order.getFilledShares());
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean zero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal averageRestingSeconds(List<TradeOrderEntity> orders) {
        List<BigDecimal> durations = orders.stream()
                .filter(order -> order.getCompletedAt() != null)
                .map(order -> {
                    Instant start = order.getSubmittedAt() == null ? order.getCreatedAt() : order.getSubmittedAt();
                    if (start == null) {
                        return null;
                    }
                    return new BigDecimal(java.time.Duration.between(start, order.getCompletedAt()).toMillis())
                            .divide(new BigDecimal("1000"), SCALE, RoundingMode.HALF_UP);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (durations.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = durations.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(new BigDecimal(durations.size()), SCALE, RoundingMode.HALF_UP);
    }

    private String clientOrderId(TradeIntent intent, Long tradeId) {
        return "BACKTEST:" + runId + ":" + tradeId + ":" + intent.side() + ":" + intent.tokenId() + ":" + TimeMachine.now().toEpochMilli();
    }

    private record MakerFillEstimate(BigDecimal shares, BigDecimal price, boolean fullFill, String reason) {
    }
}
