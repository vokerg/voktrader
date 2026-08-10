package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusMapper;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Durable cancellation coordinator. Cancellation intent and every remote-boundary
 * state are committed independently so a restart can resume without blind retrying
 * an ambiguous cancel.
 */
@Slf4j
@Service
public class DurableOrderCancellationService {
    private static final List<TradeOrderStatus> CANCELLATION_RECOVERY_STATUSES = Arrays.stream(TradeOrderStatus.values())
            .filter(status -> status.isActive() || status == TradeOrderStatus.UNKNOWN)
            .toList();

    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PythonExecutorClient executorClient;
    private final OrderReconciliationService reconciliationService;
    private final OrderCancellationEventEmitter cancellationEventEmitter;
    private final TransactionOperations transactions;
    private final Duration cancelSubmittingStaleAfter;

    @Autowired
    public DurableOrderCancellationService(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter cancellationEventEmitter,
            ExecutorProperties executorProperties,
            PlatformTransactionManager transactionManager
    ) {
        this(
                tradeRepository,
                tradeOrderRepository,
                executorClient,
                reconciliationService,
                cancellationEventEmitter,
                new TransactionTemplate(transactionManager),
                recoveryDelay(executorProperties)
        );
    }

    DurableOrderCancellationService(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter cancellationEventEmitter,
            TransactionOperations transactions
    ) {
        this(
                tradeRepository,
                tradeOrderRepository,
                executorClient,
                reconciliationService,
                cancellationEventEmitter,
                transactions,
                Duration.ofSeconds(12)
        );
    }

    DurableOrderCancellationService(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter cancellationEventEmitter,
            TransactionOperations transactions,
            Duration cancelSubmittingStaleAfter
    ) {
        this.tradeRepository = tradeRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.executorClient = executorClient;
        this.reconciliationService = reconciliationService;
        this.cancellationEventEmitter = cancellationEventEmitter;
        this.transactions = transactions;
        this.cancelSubmittingStaleAfter = cancelSubmittingStaleAfter == null
                ? Duration.ofSeconds(12)
                : cancelSubmittingStaleAfter;
    }

    public OrderLifecycleResult cancel(String localOrRemoteOrderId, String reason) {
        PendingCancellation pending = transactions.execute(status -> persistRequest(localOrRemoteOrderId, reason));
        if (pending == null) {
            throw new IllegalStateException("Cancellation request transaction returned no result");
        }
        if (!pending.recoverable()) {
            return currentResult(pending.orderId(), true, "order is already terminal; cancellation not required");
        }
        return advanceCancellation(pending.orderId());
    }

    public int resumePendingCancellations() {
        Instant now = TimeMachine.now();
        List<TradeOrderEntity> pending = tradeOrderRepository.findByCancelReasonIsNotNullAndStatusIn(
                CANCELLATION_RECOVERY_STATUSES
        );
        int resumed = 0;
        for (TradeOrderEntity order : pending) {
            if (order.getId() == null || order.getStatus().isTerminal() || order.isReconciliationPaused()) {
                continue;
            }
            if (order.getNextReconcileAt() != null && now.isBefore(order.getNextReconcileAt())) {
                continue;
            }
            if (order.getStatus() == TradeOrderStatus.CANCEL_SUBMITTING && !isStaleSubmitting(order, now)) {
                continue;
            }
            try {
                advanceCancellation(order.getId());
                resumed++;
            } catch (RuntimeException exception) {
                log.warn("Failed to resume cancellation orderId={} localOrderId={} status={}",
                        order.getId(), order.getLocalOrderId(), order.getStatus(), exception);
            }
        }
        return resumed;
    }

    private PendingCancellation persistRequest(String localOrRemoteOrderId, String reason) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId);
        if (order.getStatus().isTerminal()) {
            return new PendingCancellation(order.getId(), order.getLocalOrderId(), order.getRemoteOrderId(), false);
        }
        TradeEntity trade = findTrade(order);
        String cancelReason = normalizeReason(reason);
        if (order.getStatus().isCancellationInFlight()) {
            return new PendingCancellation(order.getId(), order.getLocalOrderId(), order.getRemoteOrderId(), true);
        }

        if (order.getCancelReason() != null && !order.getCancelReason().isBlank()) {
            TradeOrderStatus previousStatus = order.getStatus();
            transitionStatus(order, TradeOrderStatus.CANCEL_RECONCILE, null);
            tradeOrderRepository.save(order);
            cancellationEventEmitter.emitCancelStateChanged(
                    trade,
                    order,
                    previousStatus,
                    "existing cancellation intent resumed before retry",
                    null
            );
        } else {
            order.markCancelRequested(cancelReason);
            tradeOrderRepository.save(order);
            cancellationEventEmitter.emitCancelRequested(trade, order, cancelReason, null);
        }
        return new PendingCancellation(order.getId(), order.getLocalOrderId(), order.getRemoteOrderId(), true);
    }

    private OrderLifecycleResult advanceCancellation(Long orderId) {
        TradeOrderEntity order = requireOrder(orderId);
        if (order.getStatus().isTerminal()) {
            return currentResult(orderId, true, "cancellation resolved to terminal order state");
        }
        return switch (order.getStatus()) {
            case CANCEL_REQUESTED -> dispatchCancellation(orderId);
            case CANCEL_SUBMITTING -> {
                if (!isStaleSubmitting(order, TimeMachine.now())) {
                    yield currentResult(orderId, true, "cancellation dispatch is already in progress");
                }
                transitionCancellationState(
                        orderId,
                        TradeOrderStatus.CANCEL_UNKNOWN,
                        "recovered stale CANCEL_SUBMITTING after restart or lost response",
                        null
                );
                transitionCancellationState(
                        orderId,
                        TradeOrderStatus.CANCEL_RECONCILE,
                        "ambiguous cancellation must reconcile before retry",
                        null
                );
                yield reconcileCancellation(orderId);
            }
            case CANCEL_ACKNOWLEDGED -> {
                transitionCancellationState(
                        orderId,
                        TradeOrderStatus.CANCEL_RECONCILE,
                        "cancel acknowledgement requires exchange-truth reconciliation",
                        order.getRawResponse()
                );
                yield reconcileCancellation(orderId);
            }
            case CANCEL_UNKNOWN -> {
                transitionCancellationState(
                        orderId,
                        TradeOrderStatus.CANCEL_RECONCILE,
                        "unknown cancellation outcome requires reconciliation",
                        order.getRawResponse()
                );
                yield reconcileCancellation(orderId);
            }
            case CANCEL_RECONCILE -> reconcileCancellation(orderId);
            default -> {
                if (order.getCancelReason() != null && !order.getCancelReason().isBlank()) {
                    transitionCancellationState(
                            orderId,
                            TradeOrderStatus.CANCEL_RECONCILE,
                            "restored persisted cancellation intent after lifecycle projection",
                            order.getRawResponse()
                    );
                    yield reconcileCancellation(orderId);
                }
                yield currentResult(orderId, false, "order has no recoverable cancellation intent");
            }
        };
    }

    private OrderLifecycleResult dispatchCancellation(Long orderId) {
        DispatchClaim claim = transactions.execute(status -> claimDispatch(orderId));
        if (claim == null) {
            throw new IllegalStateException("Cancellation dispatch transaction returned no result");
        }
        if (!claim.claimed()) {
            return currentResult(orderId, true, "cancellation dispatch is already claimed or resolved");
        }
        if (claim.remoteOrderId() == null || claim.remoteOrderId().isBlank()) {
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_UNKNOWN,
                    "cancel dispatch has no remote order id",
                    null
            );
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_RECONCILE,
                    "missing remote order id requires reconciliation before retry",
                    null
            );
            return reconcileCancellation(orderId);
        }

        ExecutorCancelOrderResponse response;
        try {
            response = executorClient.cancelOrder(claim.remoteOrderId());
        } catch (RuntimeException exception) {
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_UNKNOWN,
                    "cancel executor call threw: " + safeMessage(exception),
                    null
            );
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_RECONCILE,
                    "thrown cancel outcome requires reconciliation before retry",
                    null
            );
            return reconcileCancellation(orderId);
        }

        if (response != null && response.success()) {
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_ACKNOWLEDGED,
                    firstNonBlank(response.status(), "cancel acknowledged by executor"),
                    response.rawResponse()
            );
        } else {
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_UNKNOWN,
                    cancelFailureDetails(response),
                    response == null ? null : response.rawResponse()
            );
        }
        transitionCancellationState(
                orderId,
                TradeOrderStatus.CANCEL_RECONCILE,
                "remote cancel outcome must reconcile against exchange truth",
                response == null ? null : response.rawResponse()
        );
        return reconcileCancellation(orderId);
    }

    private DispatchClaim claimDispatch(Long orderId) {
        TradeOrderEntity order = tradeOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order disappeared before cancellation dispatch: " + orderId));
        if (order.getStatus() != TradeOrderStatus.CANCEL_REQUESTED) {
            return new DispatchClaim(false, order.getRemoteOrderId());
        }
        TradeOrderStatus previousStatus = order.getStatus();
        transitionStatus(order, TradeOrderStatus.CANCEL_SUBMITTING, null);
        tradeOrderRepository.save(order);
        cancellationEventEmitter.emitCancelStateChanged(
                findTrade(order),
                order,
                previousStatus,
                "cancel dispatch claimed before executor call",
                null
        );
        return new DispatchClaim(true, order.getRemoteOrderId());
    }

    private OrderLifecycleResult reconcileCancellation(Long orderId) {
        TradeOrderEntity order = requireOrder(orderId);
        if (order.getStatus().isTerminal()) {
            return currentResult(orderId, true, "cancellation resolved to terminal order state");
        }
        OrderReconciliationResult result = reconciliationService.reconcileOrderDetailed(
                order,
                OrderReconciliationSource.POST_CANCEL
        );
        TradeOrderEntity reconciled = requireOrder(orderId);
        if (reconciled.getStatus().isTerminal()) {
            return currentResult(
                    orderId,
                    result.remoteStatusSuccess() || result.remoteFillsSuccess(),
                    "cancellation reconciliation reached terminal state " + reconciled.getStatus()
            );
        }

        if (remoteConfirmsStillOpen(result)) {
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_REQUESTED,
                    "remote order is still open after reconciliation; cancel retry queued",
                    reconciled.getRawResponse()
            );
            return currentResult(orderId, true, "remote order still open; cancellation retry queued");
        }

        if (reconciled.getStatus() != TradeOrderStatus.CANCEL_RECONCILE) {
            transitionCancellationState(
                    orderId,
                    TradeOrderStatus.CANCEL_RECONCILE,
                    "cancellation remains unresolved after reconciliation",
                    reconciled.getRawResponse()
            );
        }
        return currentResult(
                orderId,
                result.remoteStatusSuccess() || result.remoteFillsSuccess(),
                "cancellation reconciliation remains pending"
        );
    }

    private void transitionCancellationState(
            Long orderId,
            TradeOrderStatus targetStatus,
            String reason,
            String rawResponse
    ) {
        transactions.executeWithoutResult(status -> {
            TradeOrderEntity order = tradeOrderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new IllegalStateException("Order disappeared during cancellation: " + orderId));
            if (order.getStatus().isTerminal()) {
                return;
            }
            TradeOrderStatus previousStatus = order.getStatus();
            if (previousStatus == targetStatus && !isUsefulRaw(rawResponse)) {
                return;
            }
            transitionStatus(order, targetStatus, rawResponse);
            tradeOrderRepository.save(order);
            cancellationEventEmitter.emitCancelStateChanged(
                    findTrade(order),
                    order,
                    previousStatus,
                    reason,
                    rawResponse
            );
        });
    }

    private void transitionStatus(TradeOrderEntity order, TradeOrderStatus status, String rawResponse) {
        order.applyFillState(
                status,
                order.getAvgFillPrice(),
                order.getFilledShares(),
                order.getFilledAmountUsd(),
                order.getRemainingShares(),
                order.getRealizedFeeUsd(),
                Boolean.TRUE.equals(order.getFeeKnown()),
                order.getFillRole(),
                rawResponse
        );
    }

    private boolean remoteConfirmsStillOpen(OrderReconciliationResult result) {
        if (!result.remoteStatusSuccess()) {
            return false;
        }
        TradeOrderStatus remote = ExecutorOrderStatusMapper.toLifecycleStatus(result.remoteStatus());
        return switch (remote) {
            case CREATED, SUBMITTING, SUBMITTED, RESTING, OPEN, PARTIALLY_FILLED, PARTIAL -> true;
            default -> false;
        };
    }

    private boolean isStaleSubmitting(TradeOrderEntity order, Instant now) {
        Instant updatedAt = order.getUpdatedAt();
        return updatedAt == null || !now.isBefore(updatedAt.plus(cancelSubmittingStaleAfter));
    }

    private OrderLifecycleResult currentResult(Long orderId, boolean success, String message) {
        TradeOrderEntity order = requireOrder(orderId);
        return OrderLifecycleResult.of(findTrade(order), order, success, message);
    }

    private TradeOrderEntity requireOrder(Long orderId) {
        return tradeOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found during cancellation: " + orderId));
    }

    private TradeOrderEntity findOrder(String localOrRemoteOrderId) {
        return tradeOrderRepository.findByLocalOrderId(localOrRemoteOrderId)
                .or(() -> tradeOrderRepository.findByClientOrderId(localOrRemoteOrderId))
                .or(() -> tradeOrderRepository.findByRemoteOrderId(localOrRemoteOrderId))
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
    }

    private TradeEntity findTrade(TradeOrderEntity order) {
        return order.getTradeId() == null
                ? null
                : tradeRepository.findById(order.getTradeId()).orElse(null);
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "manual/explicit cancel requested" : reason;
    }

    private String cancelFailureDetails(ExecutorCancelOrderResponse response) {
        if (response == null) {
            return "executor returned no cancel response";
        }
        if (response.error() != null && response.error().message() != null && !response.error().message().isBlank()) {
            return response.error().message();
        }
        return firstNonBlank(response.status(), "executor returned unsuccessful cancel response");
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private boolean isUsefulRaw(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
    }

    private static Duration recoveryDelay(ExecutorProperties properties) {
        Duration timeout = properties == null || properties.getTimeout() == null
                ? Duration.ofSeconds(10)
                : properties.getTimeout();
        return timeout.plusSeconds(2);
    }

    private record PendingCancellation(Long orderId, String localOrderId, String remoteOrderId, boolean recoverable) {
    }

    private record DispatchClaim(boolean claimed, String remoteOrderId) {
    }
}
