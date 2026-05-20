package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderManager {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final PythonExecutorClient pythonExecutorClient;
    private final ExecutorProperties executorProperties;
    private final OrderReconciliationService reconciliationService;
    private final OrderCancellationEventEmitter cancellationEventEmitter;
    private final ObjectMapper objectMapper;

    @Transactional
    public OrderLifecycleResult submitOrder(TradeIntent intent, ExecutionMode mode) {
        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        String localOrderId = localOrderId(intent, mode, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(),
                intent,
                mode,
                TradeVenue.POLYMARKET,
                localOrderId
        ));

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, localOrderId, executorProperties.isDryRun());
        order.markSubmitting(command.idempotencyKey(), command.toString());
        order = tradeOrderRepository.save(order);

        ExecutorOrderResponse response = pythonExecutorClient.submit(command);
        order.attachExecutorResponse(response.exchangeOrderId(), response.rawResponse());
        if (!response.accepted()) {
            order.markRejected(response.safeMessage(), response.rawResponse());
            trade.markFailed(response.safeMessage());
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            return OrderLifecycleResult.of(trade, order, false, response.safeMessage());
        }

        if (response.filled()) {
            order.markFilled(response.exchangeOrderId(), response.averagePrice(), response.filledShares(), response.filledAmountUsd());
            tradeOrderRepository.save(order);
            persistImmediateFillIfAbsent(trade, order, intent, response);
            reconciliationService.applyImmediateFill(trade, order, response);
            try {
                reconciliationService.reconcileOrder(order, OrderReconciliationSource.POST_FILL_AUDIT);
            } catch (RuntimeException ignored) {
                // Post-fill audit is observability; the accepted fill path must not depend on remote history lag.
            }
            return OrderLifecycleResult.of(trade, order, true, response.safeMessage());
        }

        order.markSubmitted(response.exchangeOrderId(), response.rawResponse());
        if (intent.side() == TradeSide.BUY) {
            trade.markEntryPending();
        } else {
            trade.markExitPending();
        }
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        reconciliationService.reconcileOrder(order, OrderReconciliationSource.POST_SUBMIT);
        return OrderLifecycleResult.of(trade, order, true, response.safeMessage());
    }

    private void persistImmediateFillIfAbsent(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeIntent intent,
            ExecutorOrderResponse response
    ) {
        boolean alreadyRecorded = tradeFillRepository.findByOrderId(order.getId()).stream()
                .anyMatch(fill -> equalString(response.exchangeOrderId(), fill.getExchangeOrderId())
                        && intent.side() == fill.getSide()
                        && equalByValue(response.averagePrice(), fill.getPrice())
                        && equalByValue(response.filledShares(), fill.getShares()));
        if (alreadyRecorded) {
            return;
        }
        tradeFillRepository.save(TradeFillEntity.polymarket(
                trade.getId(),
                order.getId(),
                response.exchangeOrderId(),
                intent.side(),
                response.averagePrice(),
                response.filledShares(),
                response.filledAmountUsd(),
                response.feeUsd(),
                response.rawResponse()
        ));
    }

    @Transactional
    public OrderLifecycleResult cancelOrder(String localOrRemoteOrderId) {
        return cancelOrder(localOrRemoteOrderId, "manual/explicit cancel requested");
    }

    @Transactional
    public OrderLifecycleResult cancelOrder(String localOrRemoteOrderId, String reason) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        String cancelReason = reason == null || reason.isBlank() ? "manual/explicit cancel requested" : reason;
        order.markCancelRequested(cancelReason);
        tradeOrderRepository.save(order);
        cancellationEventEmitter.emitCancelRequested(trade, order, cancelReason, null);

        ExecutorCancelOrderResponse response = pythonExecutorClient.cancelOrder(order.getRemoteOrderId());
        if (isUsefulRaw(response.rawResponse())) {
            order.attachExecutorResponse(null, response.rawResponse());
        }
        tradeOrderRepository.save(order);
        if (!response.success()) {
            order.markUnknown(response.error() == null ? response.status() : response.error().message());
            tradeOrderRepository.save(order);
        }
        return reconciliationService.reconcileOrder(order, OrderReconciliationSource.POST_CANCEL);
    }

    @Transactional(readOnly = true)
    public Optional<TradeOrderEntity> getOrderState(String localOrRemoteOrderId) {
        return findOrder(localOrRemoteOrderId);
    }

    @Transactional
    public OrderLifecycleResult reconcileOrder(String localOrRemoteOrderId) {
        return reconcileOrder(localOrRemoteOrderId, OrderReconciliationSource.AUTO_WORKER);
    }

    @Transactional
    public OrderLifecycleResult reconcileOrder(String localOrRemoteOrderId, OrderReconciliationSource source) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
        return reconciliationService.reconcileOrder(order, source);
    }

    @Transactional
    public OrderReconciliationResult reconcileOrderDetailed(String localOrRemoteOrderId, OrderReconciliationSource source) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
        return reconciliationService.reconcileOrderDetailed(order, source);
    }

    @Transactional
    public OrderReconciliationResult reconcileOrderDetailed(Long orderId, OrderReconciliationSource source) {
        TradeOrderEntity order = tradeOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return reconciliationService.reconcileOrderDetailed(order, source);
    }

    @Transactional
    public int reconcileOpenOrders() {
        return reconcileOpenOrders(OrderReconciliationSource.AUTO_WORKER);
    }

    @Transactional
    public int reconcileOpenOrders(OrderReconciliationSource source) {
        return reconciliationService.reconcileOpenOrders(source);
    }

    private Optional<TradeOrderEntity> findOrder(String localOrRemoteOrderId) {
        return tradeOrderRepository.findByLocalOrderId(localOrRemoteOrderId)
                .or(() -> tradeOrderRepository.findByClientOrderId(localOrRemoteOrderId))
                .or(() -> tradeOrderRepository.findByRemoteOrderId(localOrRemoteOrderId));
    }

    private String localOrderId(TradeIntent intent, ExecutionMode mode, Long tradeId) {
        Instant decisionAt = intent.decisionAt() == null ? Instant.now() : intent.decisionAt();
        String botScope = intent.botId() == null ? "default" : intent.botId().toString();
        return "ORDER:" + mode + ":" + botScope + ":" + intent.strategyId() + ":" + tradeId + ":" + decisionAt.toEpochMilli();
    }

    private java.math.BigDecimal zeroIfNull(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private boolean equalByValue(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private boolean equalString(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equals(right);
    }

    private boolean isUsefulRaw(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
    }
}
