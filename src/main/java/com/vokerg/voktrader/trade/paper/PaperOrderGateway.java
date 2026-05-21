package com.vokerg.voktrader.trade.paper;

import com.vokerg.voktrader.backtest.BacktestFillModel;
import com.vokerg.voktrader.bot.BotRuntimeContext;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeExecutionSafetyService;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeStateProvider;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.simulation.BookOrderFillSimulator;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class PaperOrderGateway implements OrderGateway, TradeStateProvider {
    private static final int SCALE = 8;
    private static final List<TradeStatus> ACTIVE_STATUSES = List.of(
            TradeStatus.ENTRY_PENDING,
            TradeStatus.PARTIALLY_OPEN,
            TradeStatus.OPEN,
            TradeStatus.EXIT_PENDING,
            TradeStatus.PARTIALLY_CLOSED
    );
    private static final List<TradeOrderStatus> ACTIVE_ORDER_STATUSES = List.of(
            TradeOrderStatus.RESTING,
            TradeOrderStatus.PARTIALLY_FILLED,
            TradeOrderStatus.SUBMITTED
    );

    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final PolymarketFeeCalculator feeCalculator;
    private final TradingProperties tradingProperties;
    private final PaperExecutionProperties paperExecutionProperties;
    private final BookOrderFillSimulator fillSimulator;
    private final TradeExecutionSafetyService safetyService;
    private final PaperOrderEventEmitter paperOrderEventEmitter;

    public PaperOrderGateway(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            TradeFillRepository tradeFillRepository,
            PolymarketFeeCalculator feeCalculator,
            TradingProperties tradingProperties,
            PaperExecutionProperties paperExecutionProperties,
            BookOrderFillSimulator fillSimulator,
            TradeExecutionSafetyService safetyService,
            PaperOrderEventEmitter paperOrderEventEmitter
    ) {
        this.tradeRepository = tradeRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.tradeFillRepository = tradeFillRepository;
        this.feeCalculator = feeCalculator;
        this.tradingProperties = tradingProperties;
        this.paperExecutionProperties = paperExecutionProperties;
        this.fillSimulator = fillSimulator;
        this.safetyService = safetyService;
        this.paperOrderEventEmitter = paperOrderEventEmitter;
    }

    @Override
    @Transactional
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        TradeEntity trade = intent.side() == TradeSide.BUY ? createEntryTrade(intent) : findExitTrade(intent).orElse(null);
        if (trade == null) {
            return new OrderLifecycleResult(false, null, null, null, null, null, null,
                    "paper sell rejected: no open paper trade", "paper sell rejected: no open paper trade");
        }
        if (intent.side() == TradeSide.SELL && trade.getMode() != ExecutionMode.PAPER) {
            return new OrderLifecycleResult(false, trade.getId(), null, null, null, trade.getStatus(), null,
                    "paper sell rejected: non-paper trade", "paper sell rejected: non-paper trade");
        }
        if (intent.side() == TradeSide.SELL) {
            Optional<TradeExecutionResult> blocked = safetyService.rejectPaperExitIfLiveBacked(
                    trade,
                    intent,
                    ExecutionMode.PAPER,
                    "PaperOrderGateway.submitOrder"
            );
            if (blocked.isPresent()) {
                TradeExecutionResult result = blocked.get();
                return new OrderLifecycleResult(
                        false,
                        result.tradeId(),
                        result.orderId(),
                        null,
                        null,
                        result.tradeStatus(),
                        result.orderStatus(),
                        result.message(),
                        result.error()
                );
            }
        }

        String localOrderId = localOrderId(intent, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(),
                intent,
                ExecutionMode.PAPER,
                TradeVenue.PAPER_SIM,
                localOrderId
        ));
        order.markSubmitting(localOrderId, intent.toString());
        order = tradeOrderRepository.save(order);

        if (intent.orderType().expectsImmediateFill()) {
            return executeImmediate(intent, trade, order);
        }

        if (intent.restingTtlSeconds() != null && intent.restingTtlSeconds() > 0) {
            order.setExpiresAt(TimeMachine.now().plusSeconds(intent.restingTtlSeconds()));
        } else if (intent.orderType() == TradeOrderType.GTD) {
            order.setExpiresAt(TimeMachine.now().plusSeconds(paperExecutionProperties.getDefaultGtdSeconds()));
        }
        order.markResting("{\"paper\":true,\"fillModel\":\"" + paperExecutionProperties.getFillModel() + "\"}");
        if (intent.side() == TradeSide.BUY) {
            trade.markEntryPending();
        } else {
            trade.markExitPending();
        }
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        paperOrderEventEmitter.emitResting(trade, order, currentBook(intent.tokenId()), "RESTING", paperExecutionProperties.fillModel());
        attemptImmediateAdvance(order, trade);
        return OrderLifecycleResult.of(trade, order, true, "paper order resting");
    }

    @Override
    @Transactional
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        TradeOrderEntity order = findOrder(localOrderId).orElse(null);
        if (order == null) {
            return new OrderLifecycleResult(false, null, null, localOrderId, null, null, null, "paper cancel rejected: order not found", "order not found");
        }
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        if (!paperScopeOk(order, trade)) {
            return OrderLifecycleResult.of(trade, order, false, "paper cancel rejected: non-paper order/trade");
        }
        TradeOrderStatus previousStatus = order.getStatus();
        paperOrderEventEmitter.emitCancelRequested(trade, order, currentBook(order.getTokenId()), reasonKey(reason, "STRATEGY_CANCELLED"), paperExecutionProperties.fillModel());
        order.markCancelled(reason, "{\"paper\":true,\"cancelled\":true}");
        if (trade != null) {
            if (order.getPhase() == TradeOrderPhase.ENTRY) {
                if (zero(order.getFilledShares())) {
                    trade.markCancelled();
                } else {
                    trade.markPartiallyOpen(
                            firstNonNull(trade.getEntryAvgPrice(), order.getAvgFillPrice()),
                            firstNonNull(trade.getEntryFilledShares(), order.getFilledShares()),
                            firstNonNull(trade.getEntryFilledUsd(), order.getFilledAmountUsd()),
                            firstNonNull(trade.getEntryFeeUsd(), order.getRealizedFeeUsd(), BigDecimal.ZERO),
                            TimeMachine.now()
                    );
                }
                tradeRepository.save(trade);
            } else if (order.getPhase() == TradeOrderPhase.EXIT) {
                if (zero(order.getFilledShares())) {
                    if (trade.getEntryFilledShares() != null && trade.getEntryFilledShares().compareTo(BigDecimal.ZERO) > 0) {
                        trade.markOpen(
                                trade.getEntryAvgPrice(),
                                trade.getEntryFilledShares(),
                                trade.getEntryFilledUsd(),
                                trade.getEntryFeeUsd(),
                                firstNonNull(trade.getEntryCompletedAt(), TimeMachine.now())
                        );
                    }
                } else {
                    trade.markPartiallyClosed(
                            firstNonNull(trade.getExitAvgPrice(), order.getAvgFillPrice()),
                            firstNonNull(trade.getExitFilledShares(), order.getFilledShares()),
                            firstNonNull(trade.getExitFilledUsd(), order.getFilledAmountUsd()),
                            firstNonNull(trade.getExitFeeUsd(), order.getRealizedFeeUsd(), BigDecimal.ZERO),
                            TimeMachine.now()
                    );
                }
                tradeRepository.save(trade);
            }
        }
        tradeOrderRepository.save(order);
        paperOrderEventEmitter.emitCancelled(trade, order, previousStatus, currentBook(order.getTokenId()), reasonKey(reason, "STRATEGY_CANCELLED"), paperExecutionProperties.fillModel());
        return OrderLifecycleResult.of(trade, order, true, reason);
    }

    @Override
    @Transactional
    public void advanceOpenOrders() {
        if (!paperExecutionProperties.isAdvanceOpenOrdersOnBookUpdate()) {
            return;
        }
        var context = BotRuntimeContextHolder.current().orElse(null);
        if (context == null) {
            return;
        }
        Long botId = context.botId();
        OrderBookState orderBookState = context.orderBookState();
        String currentMarketId = context.trackedMarketState() == null
                ? null
                : context.trackedMarketState().currentMarket().map(com.vokerg.voktrader.polymarket.dto.GammaMarketDto::id).orElse(null);
        if (orderBookState == null) {
            return;
        }
        List<TradeOrderEntity> orders = tradeOrderRepository
                .findByModeAndVenueAndStatusInOrderByUpdatedAtAsc(
                        ExecutionMode.PAPER,
                        TradeVenue.PAPER_SIM,
                        ACTIVE_ORDER_STATUSES
                )
                .stream()
                .filter(order -> Objects.equals(order.getBotId(), botId))
                .filter(order -> currentMarketId == null || Objects.equals(order.getMarketId(), currentMarketId))
                .toList();
        for (TradeOrderEntity order : orders) {
            advanceOneOrder(order, orderBookState.byTokenId(order.getTokenId()).orElse(null));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public StrategyRuntimeState getState(StrategyInstanceKey owner, String marketId) {
        if (owner == null) {
            return StrategyRuntimeState.empty(null, marketId);
        }
        Optional<TradeEntity> trade = owner.botId() == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(owner.strategyId(), marketId, ACTIVE_STATUSES)
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(owner.botId(), owner.strategyId(), marketId, ACTIVE_STATUSES);
        return trade.map(entity -> stateFromTrade(owner, entity))
                .orElseGet(() -> StrategyRuntimeState.empty(owner, marketId));
    }

    private OrderLifecycleResult executeImmediate(TradeIntent intent, TradeEntity trade, TradeOrderEntity order) {
        OutcomeOrderBook book = BotRuntimeContextHolder.currentOrderBookState()
                .flatMap(state -> state.byTokenId(intent.tokenId()))
                .orElse(null);
        FillEstimate estimate = fillSimulator.immediateFill(intent, book);
        if (estimate == null || estimate.filledShares().compareTo(BigDecimal.ZERO) <= 0) {
            order.markCancelled("paper immediate order did not fill", "{\"paper\":true,\"filled\":false}");
            if (intent.side() == TradeSide.BUY) {
                trade.markCancelled();
            }
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            return OrderLifecycleResult.of(trade, order, false, "paper immediate order did not fill");
        }
        if (intent.orderType() == TradeOrderType.FOK && !estimate.complete()) {
            order.markCancelled("paper FOK insufficient depth", "{\"paper\":true,\"filled\":false}");
            if (intent.side() == TradeSide.BUY) {
                trade.markCancelled();
            }
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            return OrderLifecycleResult.of(trade, order, false, "paper FOK insufficient depth");
        }
        BigDecimal feeUsd = feeFor(LiquidityRole.TAKER, estimate.filledShares(), estimate.averagePrice());
        fillOrder(
                trade,
                order,
                estimate.averagePrice(),
                estimate.filledShares(),
                LiquidityRole.TAKER,
                feeUsd,
                estimate.complete(),
                book,
                estimate.complete() ? "FULL_FILL" : "PARTIAL_FILL"
        );
        return OrderLifecycleResult.of(trade, order, true, estimate.complete() ? "paper immediate filled" : "paper FAK partially filled");
    }

    private void fillOrder(
            TradeEntity trade,
            TradeOrderEntity order,
            BigDecimal price,
            BigDecimal shares,
            LiquidityRole role,
            BigDecimal feeUsd,
            boolean full,
            OutcomeOrderBook book,
            String reason
    ) {
        if (trade == null || price == null || shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!paperScopeOk(order, trade)) {
            return;
        }
        TradeOrderStatus previousStatus = order.getStatus();
        BigDecimal previousShares = nullToZero(order.getFilledShares());
        BigDecimal cumulativeShares = previousShares.add(shares);
        BigDecimal fillAmountUsd = price.multiply(shares).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal cumulativeAmountUsd = nullToZero(order.getFilledAmountUsd()).add(fillAmountUsd).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal cumulativeFeeUsd = nullToZero(order.getRealizedFeeUsd()).add(nullToZero(feeUsd)).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal remainingShares = fillSimulator.remainingShares(order).subtract(shares);
        if (remainingShares.compareTo(BigDecimal.ZERO) < 0) {
            remainingShares = BigDecimal.ZERO;
        }
        boolean cumulativeFull = full || remainingShares.compareTo(BigDecimal.ZERO) <= 0;
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.paper(
                trade.getId(),
                order.getId(),
                order.getSide(),
                price,
                shares,
                fillAmountUsd,
                nullToZero(feeUsd),
                role.name(),
                "{\"paper\":true,\"fillModel\":\"" + paperExecutionProperties.getFillModel() + "\"}"
        ));
        order.applyFillState(
                cumulativeFull ? TradeOrderStatus.FILLED : TradeOrderStatus.PARTIALLY_FILLED,
                price,
                cumulativeShares,
                cumulativeAmountUsd,
                cumulativeFull ? BigDecimal.ZERO : remainingShares,
                cumulativeFeeUsd,
                true,
                role,
                fill.getRawFill()
        );
        if (order.getSide() == TradeSide.BUY) {
            if (cumulativeFull) {
                trade.markOpen(price, cumulativeShares, cumulativeAmountUsd, cumulativeFeeUsd, TimeMachine.now());
            } else {
                trade.markPartiallyOpen(price, cumulativeShares, cumulativeAmountUsd, cumulativeFeeUsd, TimeMachine.now());
            }
        } else if (cumulativeFull) {
            safetyService.assertPaperMayCloseTrade(trade, null, "PaperOrderGateway.fillOrder");
            trade.markClosed(price, cumulativeShares, cumulativeAmountUsd, cumulativeFeeUsd, TimeMachine.now());
        } else {
            trade.markPartiallyClosed(price, cumulativeShares, cumulativeAmountUsd, cumulativeFeeUsd, TimeMachine.now());
        }
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        paperOrderEventEmitter.emitFilled(
                trade,
                order,
                previousStatus,
                book,
                reason == null ? (cumulativeFull ? "FULL_FILL" : "PARTIAL_FILL") : reason,
                paperExecutionProperties.fillModel(),
                cumulativeFull
        );
    }

    private void expire(TradeOrderEntity order, OutcomeOrderBook book) {
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        if (!paperScopeOk(order, trade)) {
            return;
        }
        TradeOrderStatus previousStatus = order.getStatus();
        order.markExpired("{\"paper\":true,\"expired\":true}");
        if (trade != null) {
            if (order.getPhase() == TradeOrderPhase.ENTRY) {
                if (zero(order.getFilledShares())) {
                    trade.markCancelled();
                } else {
                    trade.markPartiallyOpen(
                            firstNonNull(trade.getEntryAvgPrice(), order.getAvgFillPrice()),
                            firstNonNull(trade.getEntryFilledShares(), order.getFilledShares()),
                            firstNonNull(trade.getEntryFilledUsd(), order.getFilledAmountUsd()),
                            firstNonNull(trade.getEntryFeeUsd(), order.getRealizedFeeUsd(), BigDecimal.ZERO),
                            TimeMachine.now()
                    );
                }
            } else if (order.getPhase() == TradeOrderPhase.EXIT && zero(order.getFilledShares())) {
                trade.markOpen(
                        trade.getEntryAvgPrice(),
                        trade.getEntryFilledShares(),
                        trade.getEntryFilledUsd(),
                        trade.getEntryFeeUsd(),
                        firstNonNull(trade.getEntryCompletedAt(), TimeMachine.now())
                );
            } else if (order.getPhase() == TradeOrderPhase.EXIT) {
                trade.markPartiallyClosed(
                        firstNonNull(trade.getExitAvgPrice(), order.getAvgFillPrice()),
                        firstNonNull(trade.getExitFilledShares(), order.getFilledShares()),
                        firstNonNull(trade.getExitFilledUsd(), order.getFilledAmountUsd()),
                        firstNonNull(trade.getExitFeeUsd(), order.getRealizedFeeUsd(), BigDecimal.ZERO),
                        TimeMachine.now()
                );
            }
            tradeRepository.save(trade);
        }
        tradeOrderRepository.save(order);
        paperOrderEventEmitter.emitExpired(trade, order, previousStatus, book, "EXPIRED", paperExecutionProperties.fillModel());
    }

    private void advanceOneOrder(TradeOrderEntity order, OutcomeOrderBook book) {
        if (order == null || order.getStatus() == null || !ACTIVE_ORDER_STATUSES.contains(order.getStatus())) {
            return;
        }
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        if (!paperScopeOk(order, trade)) {
            return;
        }
        BacktestFillModel fillModel = paperExecutionProperties.fillModel();
        TradeOrderStatus previousStatus = order.getStatus();
        paperOrderEventEmitter.emitAdvanceAttempt(trade, order, book, "ADVANCE_ATTEMPT", fillModel);
        if (expired(order)) {
            expire(order, book);
            return;
        }
        if (fillModel == BacktestFillModel.MAKER_NO_FILL) {
            paperOrderEventEmitter.emitAdvanceNoFill(trade, order, previousStatus, order.getStatus(), book, "MAKER_NO_FILL_MODEL", fillModel);
            return;
        }
        BookOrderFillSimulator.MakerFillEstimate estimate = fillSimulator.makerFillEstimate(
                order,
                book,
                fillModel,
                paperExecutionProperties.makerTouchFillRatio()
        );
        if (estimate == null || estimate.shares().compareTo(BigDecimal.ZERO) <= 0) {
            paperOrderEventEmitter.emitAdvanceNoFill(
                    trade,
                    order,
                    previousStatus,
                    order.getStatus(),
                    book,
                    noFillReason(order, book, fillModel),
                    fillModel
            );
            return;
        }
        LiquidityRole role = LiquidityRole.MAKER;
        BigDecimal feeUsd = feeFor(role, estimate.shares(), estimate.price());
        fillOrder(
                trade,
                order,
                estimate.price(),
                estimate.shares(),
                role,
                feeUsd,
                estimate.fullFill(),
                book,
                estimate.fullFill() ? "FULL_FILL" : "PARTIAL_FILL"
        );
    }

    private boolean expired(TradeOrderEntity order) {
        Instant expiresAt = order.getExpiresAt();
        return expiresAt != null && !TimeMachine.now().isBefore(expiresAt);
    }

    private TradeEntity createEntryTrade(TradeIntent intent) {
        return tradeRepository.save(TradeEntity.fromIntent(intent, ExecutionMode.PAPER));
    }

    private Optional<TradeEntity> findExitTrade(TradeIntent intent) {
        return intent.botId() == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(
                intent.strategyId(), intent.marketId(), intent.tokenId(), List.of(TradeStatus.OPEN, TradeStatus.PARTIALLY_OPEN))
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(
                intent.botId(), intent.strategyId(), intent.marketId(), intent.tokenId(), List.of(TradeStatus.OPEN, TradeStatus.PARTIALLY_OPEN));
    }

    private Optional<TradeOrderEntity> findOrder(String localOrRemoteOrderId) {
        return tradeOrderRepository.findByLocalOrderId(localOrRemoteOrderId)
                .or(() -> tradeOrderRepository.findByClientOrderId(localOrRemoteOrderId))
                .or(() -> tradeOrderRepository.findByRemoteOrderId(localOrRemoteOrderId));
    }

    private void attemptImmediateAdvance(TradeOrderEntity order, TradeEntity trade) {
        if (order == null || trade == null || !paperScopeOk(order, trade)) {
            return;
        }
        BotRuntimeContext context = BotRuntimeContextHolder.current().orElse(null);
        if (context == null) {
            paperOrderEventEmitter.emitAdvanceAttempt(trade, order, null, "BOT_CONTEXT_MISSING", paperExecutionProperties.fillModel());
            paperOrderEventEmitter.emitAdvanceNoFill(trade, order, order.getStatus(), order.getStatus(), null, "BOT_CONTEXT_MISSING", paperExecutionProperties.fillModel());
            return;
        }
        OrderBookState orderBookState = context.orderBookState();
        if (orderBookState == null) {
            paperOrderEventEmitter.emitAdvanceAttempt(trade, order, null, "ORDER_BOOK_MISSING", paperExecutionProperties.fillModel());
            paperOrderEventEmitter.emitAdvanceNoFill(trade, order, order.getStatus(), order.getStatus(), null, "ORDER_BOOK_MISSING", paperExecutionProperties.fillModel());
            return;
        }
        OutcomeOrderBook book = orderBookState.byTokenId(order.getTokenId()).orElse(null);
        if (book == null) {
            paperOrderEventEmitter.emitAdvanceAttempt(trade, order, null, "TOKEN_BOOK_MISSING", paperExecutionProperties.fillModel());
            paperOrderEventEmitter.emitAdvanceNoFill(trade, order, order.getStatus(), order.getStatus(), null, "TOKEN_BOOK_MISSING", paperExecutionProperties.fillModel());
            return;
        }
        advanceOneOrder(order, book);
    }

    private OutcomeOrderBook currentBook(String tokenId) {
        return BotRuntimeContextHolder.currentOrderBookState()
                .flatMap(state -> state.byTokenId(tokenId))
                .orElse(null);
    }

    private boolean paperScopeOk(TradeOrderEntity order, TradeEntity trade) {
        return order != null
                && order.getMode() == ExecutionMode.PAPER
                && order.getVenue() == TradeVenue.PAPER_SIM
                && (trade == null || trade.getMode() == ExecutionMode.PAPER);
    }

    private String noFillReason(TradeOrderEntity order, OutcomeOrderBook book, BacktestFillModel fillModel) {
        if (book == null) {
            return "TOKEN_BOOK_MISSING";
        }
        if (order == null || order.getRequestedPrice() == null) {
            return "ORDER_PRICE_MISSING";
        }
        if (order.getSide() == TradeSide.BUY) {
            var bestAsk = book.bestAsk().orElse(null);
            if (bestAsk == null) {
                return "BOOK_MISSING";
            }
            int comparison = bestAsk.price().compareTo(order.getRequestedPrice());
            if (comparison > 0) {
                return "NOT_CROSSED";
            }
            if (comparison == 0 && fillModel != BacktestFillModel.MAKER_TOUCH) {
                return "TOUCH_IGNORED_BY_FILL_MODEL";
            }
            return "NO_FILL";
        }
        var bestBid = book.bestBid().orElse(null);
        if (bestBid == null) {
            return "BOOK_MISSING";
        }
        int comparison = bestBid.price().compareTo(order.getRequestedPrice());
        if (comparison < 0) {
            return "NOT_CROSSED";
        }
        if (comparison == 0 && fillModel != BacktestFillModel.MAKER_TOUCH) {
            return "TOUCH_IGNORED_BY_FILL_MODEL";
        }
        return "NO_FILL";
    }

    private String reasonKey(String freeformReason, String fallback) {
        if (freeformReason == null || freeformReason.isBlank()) {
            return fallback;
        }
        return freeformReason;
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
                exit != null && exit.fillRole() != null ? exit.fillRole() : entry == null ? null : entry.fillRole(),
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

    private BigDecimal feeFor(LiquidityRole role, BigDecimal shares, BigDecimal price) {
        BigDecimal rate = role == LiquidityRole.MAKER
                ? tradingProperties.getMakerFeeRate()
                : firstNonNull(tradingProperties.getTakerFeeRate(), tradingProperties.getPaperFeeRate(), BigDecimal.ZERO);
        return feeCalculator.estimateFeeUsd(shares, price, rate);
    }

    private String localOrderId(TradeIntent intent, Long tradeId) {
        Instant decisionAt = intent.decisionAt() == null ? Instant.now() : intent.decisionAt();
        String botScope = intent.botId() == null ? "default" : intent.botId().toString();
        return "ORDER:PAPER:" + botScope + ":" + intent.strategyId() + ":" + tradeId + ":" + decisionAt.toEpochMilli();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean zero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }
}
