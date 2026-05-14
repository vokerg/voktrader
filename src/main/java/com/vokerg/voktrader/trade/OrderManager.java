package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusMapper;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderManager {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PythonExecutorClient pythonExecutorClient;
    private final ExecutorProperties executorProperties;
    private final OrderReconciliationService reconciliationService;
    private final OrderCancellationEventEmitter cancellationEventEmitter;

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
            reconciliationService.applyImmediateFill(trade, order, response);
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
        return OrderLifecycleResult.of(trade, order, true, response.safeMessage());
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
        if (response.success()) {
            TradeOrderStatus resolvedStatus = ExecutorOrderStatusMapper.toLifecycleStatus(response.status());
            if (resolvedStatus == TradeOrderStatus.CANCELLED) {
                order.markCancelled(cancelReason, response.rawResponse());
                if (trade != null && zeroIfNull(order.getFilledShares()).compareTo(java.math.BigDecimal.ZERO) == 0) {
                    trade.markCancelled();
                    tradeRepository.save(trade);
                }
                cancellationEventEmitter.emitCancelled(
                        trade,
                        order,
                        TradeOrderStatus.CANCEL_REQUESTED,
                        TradeOrderStatus.CANCELLED,
                        order.getCancelReason(),
                        response.rawResponse()
                );
            } else {
                order.markCancelRequested("cancel accepted");
            }
            tradeOrderRepository.save(order);
            return OrderLifecycleResult.of(trade, order, true, response.status());
        }

        order.markUnknown(response.error() == null ? response.status() : response.error().message());
        tradeOrderRepository.save(order);
        return OrderLifecycleResult.of(trade, order, false, response.status());
    }

    @Transactional(readOnly = true)
    public Optional<TradeOrderEntity> getOrderState(String localOrRemoteOrderId) {
        return findOrder(localOrRemoteOrderId);
    }

    @Transactional
    public OrderLifecycleResult reconcileOrder(String localOrRemoteOrderId) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
        return reconciliationService.reconcileOrder(order);
    }

    @Transactional
    public int reconcileOpenOrders() {
        return reconciliationService.reconcileOpenOrders();
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
}
