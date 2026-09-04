package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusMapper;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.outbox.OrderDispatchStateService;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
    private static final String EVENT_RECOVERY_REASON = "recovered durable cancellation request event";
    private static final List<TradeOrderStatus> CANCELLATION_RECOVERY_STATUSES = Arrays.stream(TradeOrderStatus.values())
            .filter(status -> status.isActive() || status == TradeOrderStatus.UNKNOWN)
            .toList();

    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PythonExecutorClient executorClient;
    private final OrderReconciliationService reconciliationService;
    private final OrderCancellationEventEmitter cancellationEventEmitter;
    private final OrderDispatchStateService dispatchStateService;
    private final TransactionOperations transactions;
    private final Duration cancelSubmittingStaleAfter;

    @Autowired
    public DurableOrderCancellationService(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter cancellationEventEmitter,
            OrderDispatchStateService dispatchStateService,
            ExecutorProperties executorProperties,
            PlatformTransactionManager transactionManager
    ) {
        this(
                tradeRepository,
                tradeOrderRepository,
                executorClient,
                reconciliationService,
                cancellationEventEmitter,
                dispatchStateService,
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
                null,
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
        this(
                tradeRepository,
                tradeOrderRepository,
                executorClient,
                reconciliationService,
                cancellationEventEmitter,
                null,
                transactions,
                cancelSubmittingStaleAfter
        );
    }

    DurableOrderCancellationService(
            TradeRepository tradeRepository,
            TradeOrderRepository tradeOrderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter cancellationEventEmitter,
            OrderDispatchStateService dispatchStateService,
            TransactionOperations transactions,
            Duration cancelSubmittingStaleAfter
    ) {
        this.tradeRepository = tradeRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.executorClient = executorClient;
        this.reconciliationService = reconciliationService;
        this.cancellationEventEmitter = cancellationEventEmitter;
        this.dispatchStateService = dispatchStateService;
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
            return currentResult(pending.orderId(), true, pending.message());
        }
        return advanceCancellation(pending.orderId());
    }

    public int resumePendingCancellations() {
        Instant now = TimeMachine.now();
        List<TradeOrderEntity> pending = tradeOrderRepository.findRecoverableCancellations(
                CANCELLATION_RECOVERY_STATUSES,
                OrderCancellationEventEmitter.CANCEL_REQUESTED_EVENT
        );
        int resumed = 0;
        for (TradeOrderEntity candidate : pending) {
            if (candidate.getId() == null || candidate.getStatus().isTerminal()) {
                continue;
            }
            try {
                if (wasCancelledBeforeSubmission(candidate)) {
                    advanceCancellation(candidate.getId());
                    resumed++;
                    continue;
                }
                if (candidate.getCancelReason() == null || candidate.getCancelReason().isBlank()) {
                    restoreEventBackedCancellationIntent(candidate.getId());
                }
                TradeOrderEntity order = requireOrder(candidate.getId());
                if (order.getStatus().isTerminal() || order.isReconciliationPaused()) {
                    continue;
                }
                if (order.getNextReconcileAt() != null && now.isBefore(order.getNextReconcileAt())) {
                    continue;
                }
                if (order.getStatus() == TradeOrderStatus.CANCEL_SUBMITTING && !isStaleSubmitting(order, now)) {
                    continue;
                }
                advanceCancellation(order.getId());
                resumed++;
            } catch (RuntimeException exception) {
                log.warn("Failed to resume cancellation orderId={} localOrderId={} status={}",
                        candidate.getId(), candidate.getLocalOrderId(), candidate.getStatus(), exception);
            }
        }
        return resumed;
    }

    private PendingCancellation persistRequest(String localOrRemoteOrderId, String reason) {
        TradeOrderEntity order = findOrder(localOrRemoteOrderId);
        if (order.getStatus().isTerminal()) {
            return new PendingCancellation(
                    order.getId(),
                    false,
                    "order is already terminal; cancellation not required"
            );
        }

        String cancelReason = normalizeReason(reason);
        boolean cancelledBeforeSubmission = cancelBeforeSubmission(order, cancelReason);
        TradeEntity trade = findTrade(order);

        if (!order.getStatus().isCancellationInFlight()) {
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
        }

        if (cancelledBeforeSubmission) {
            finalizeCancelledBeforeSubmission(order, trade, cancelReason);
            return new PendingCancellation(
                    order.getId(),
                    false,
                    "order cancelled before remote submission"
            );
        }
        return new PendingCancellation(order.getId(), true, "cancellation request persisted");
    }

    private OrderLifecycleResult advanceCancellation(Long orderId) {
        TradeOrderEntity order = requireOrder(orderId);
        if (order.getStatus().isTerminal()) {
            return currentResult(orderId, true, "cancellation resolved to terminal order state");
        }
        if (wasCancelledBeforeSubmission(order)) {
            transactions.executeWithoutResult(status -> {
                TradeOrderEntity locked = tradeOrderRepository.findByIdForUpdate(orderId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Order disappeared while finalizing pre-submit cancellation: " + orderId));
                if (!locked.getStatus().isTerminal()) {
                    finalizeCancelledBeforeSubmission(
                            locked,
                            findTrade(locked),
                            firstNonBlank(locked.getCancelReason(), "cancelled before remote submission")
                    );
                }
            });
            return currentResult(orderId, true, "order cancelled before remote submission");
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
            restorePositionAfterCancelledExit(order);
            return currentResult(orderId, true, "cancellation resolved to terminal order state");
        }
        OrderReconciliationResult result = reconciliationService.reconcileOrderDetailed(
                order,
                OrderReconciliationSource.POST_CANCEL
        );
        TradeOrderEntity reconciled = requireOrder(orderId);
        if (reconciled.getStatus().isTerminal()) {
            restorePositionAfterCancelledExit(reconciled);
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

    private void restoreEventBackedCancellationIntent(Long orderId) {
        transactions.executeWithoutResult(status -> {
            TradeOrderEntity order = tradeOrderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Order disappeared while restoring cancellation intent: " + orderId));
            if (order.getStatus().isTerminal()
                    || (order.getCancelReason() != null && !order.getCancelReason().isBlank())) {
                return;
            }
            TradeOrderStatus previousStatus = order.getStatus();
            order.markCancelRequested(EVENT_RECOVERY_REASON);
            transitionStatus(order, TradeOrderStatus.CANCEL_RECONCILE, null);
            tradeOrderRepository.save(order);
            cancellationEventEmitter.emitCancelStateChanged(
                    findTrade(order),
                    order,
                    previousStatus,
                    "durable cancel-request event restored cancellation obligation after stale lifecycle write",
                    null
            );
        });
    }

    private void finalizeCancelledBeforeSubmission(
            TradeOrderEntity order,
            TradeEntity trade,
            String cancelReason
    ) {
        TradeOrderStatus previousStatus = order.getStatus();
        order.markCancelled(cancelReason, null);
        tradeOrderRepository.save(order);
        reconciliationService.reconcileTradeAfterOrderState(trade, order);
        restorePositionAfterCancelledExit(trade, order);
        if (trade != null) {
            tradeRepository.save(trade);
        }
        cancellationEventEmitter.emitCancelled(
                trade,
                order,
                previousStatus,
                TradeOrderStatus.CANCELLED,
                cancelReason,
                null
        );
    }

    private void restorePositionAfterCancelledExit(TradeOrderEntity order) {
        TradeEntity trade = findTrade(order);
        restorePositionAfterCancelledExit(trade, order);
        if (trade != null) {
            tradeRepository.save(trade);
        }
    }

    private void restorePositionAfterCancelledExit(TradeEntity trade, TradeOrderEntity order) {
        if (trade == null
                || order.getPhase() != TradeOrderPhase.EXIT
                || (order.getStatus() != TradeOrderStatus.CANCELLED
                && order.getStatus() != TradeOrderStatus.EXPIRED)
                || (trade.getStatus() != null && trade.getStatus().isTerminal())) {
            return;
        }

        BigDecimal exitedShares = zeroIfNull(trade.getExitFilledShares());
        if (exitedShares.signum() > 0) {
            trade.markPartiallyClosed(
                    trade.getExitAvgPrice(),
                    trade.getExitFilledShares(),
                    trade.getExitFilledUsd(),
                    trade.getExitFeeUsd(),
                    trade.getExitCompletedAt()
            );
            return;
        }

        BigDecimal entryShares = zeroIfNull(trade.getEntryFilledShares());
        BigDecimal intendedShares = zeroIfNull(trade.getIntendedShares());
        if (intendedShares.signum() > 0 && entryShares.compareTo(intendedShares) < 0) {
            trade.markPartiallyOpen(
                    trade.getEntryAvgPrice(),
                    trade.getEntryFilledShares(),
                    trade.getEntryFilledUsd(),
                    trade.getEntryFeeUsd(),
                    trade.getEntryCompletedAt()
            );
        } else {
            trade.markOpen(
                    trade.getEntryAvgPrice(),
                    trade.getEntryFilledShares(),
                    trade.getEntryFilledUsd(),
                    trade.getEntryFeeUsd(),
                    trade.getEntryCompletedAt()
            );
        }
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

    private boolean cancelBeforeSubmission(TradeOrderEntity order, String reason) {
        return dispatchStateService != null
                && order.getClientOrderId() != null
                && dispatchStateService.cancelBeforeSubmission(order.getClientOrderId(), reason, TimeMachine.now());
    }

    private boolean wasCancelledBeforeSubmission(TradeOrderEntity order) {
        return dispatchStateService != null
                && order.getClientOrderId() != null
                && dispatchStateService.wasCancelledBeforeSubmission(order.getClientOrderId());
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

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Duration recoveryDelay(ExecutorProperties properties) {
        Duration timeout = properties == null || properties.getTimeout() == null
                ? Duration.ofSeconds(10)
                : properties.getTimeout();
        return timeout.plusSeconds(2);
    }

    private record PendingCancellation(Long orderId, boolean recoverable, String message) {
    }

    private record DispatchClaim(boolean claimed, String remoteOrderId) {
    }
}
