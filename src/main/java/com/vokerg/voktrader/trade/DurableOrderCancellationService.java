package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Narrow T024 cancellation boundary: persist CANCEL_REQUESTED and its audit
 * event before making the remote call. T025 owns the complete evented cancel
 * state machine and restart worker.
 */
@Service
public class DurableOrderCancellationService {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PythonExecutorClient executorClient;
    private final OrderReconciliationService reconciliationService;
    private final OrderCancellationEventEmitter cancellationEventEmitter;
    private final TransactionTemplate transactionTemplate;

    public DurableOrderCancellationService(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter cancellationEventEmitter,
            PlatformTransactionManager transactionManager
    ) {
        this.tradeRepository = tradeRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.executorClient = executorClient;
        this.reconciliationService = reconciliationService;
        this.cancellationEventEmitter = cancellationEventEmitter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OrderLifecycleResult cancel(String localOrRemoteOrderId, String reason) {
        PendingCancellation pending = transactionTemplate.execute(status -> persistRequest(localOrRemoteOrderId, reason));
        if (pending == null) {
            throw new IllegalStateException("Cancellation request transaction returned no result");
        }

        ExecutorCancelOrderResponse response = executorClient.cancelOrder(pending.remoteOrderId());
        transactionTemplate.executeWithoutResult(status -> persistRemoteResponse(pending.orderId(), response));
        return reconciliationService.reconcileOrder(pending.localOrderId(), OrderReconciliationSource.POST_CANCEL);
    }

    private PendingCancellation persistRequest(String localOrRemoteOrderId, String reason) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId);
        TradeEntity trade = order.getTradeId() == null
                ? null
                : tradeRepository.findById(order.getTradeId()).orElse(null);
        String cancelReason = reason == null || reason.isBlank()
                ? "manual/explicit cancel requested"
                : reason;
        order.markCancelRequested(cancelReason);
        tradeOrderRepository.save(order);
        cancellationEventEmitter.emitCancelRequested(trade, order, cancelReason, null);
        return new PendingCancellation(order.getId(), order.getLocalOrderId(), order.getRemoteOrderId());
    }

    private void persistRemoteResponse(Long orderId, ExecutorCancelOrderResponse response) {
        TradeOrderEntity order = tradeOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order disappeared after cancellation request: " + orderId));
        if (isUsefulRaw(response.rawResponse())) {
            order.attachExecutorResponse(null, response.rawResponse());
        }
        if (!response.success()) {
            String details = response.error() == null ? response.status() : response.error().message();
            order.markUnknown(details);
        }
        tradeOrderRepository.save(order);
    }

    private TradeOrderEntity findOrder(String localOrRemoteOrderId) {
        return tradeOrderRepository.findByLocalOrderId(localOrRemoteOrderId)
                .or(() -> tradeOrderRepository.findByClientOrderId(localOrRemoteOrderId))
                .or(() -> tradeOrderRepository.findByRemoteOrderId(localOrRemoteOrderId))
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
    }

    private boolean isUsefulRaw(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
    }

    private record PendingCancellation(Long orderId, String localOrderId, String remoteOrderId) {
    }
}
