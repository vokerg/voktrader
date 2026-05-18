package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.time.TimeMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReconciliationService {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final LiveExecutionService liveExecutionService;
    private final OrderLayerProperties properties;
    private final OrderCancellationEventEmitter cancellationEventEmitter;
    private final OrderReconciliationEventEmitter reconciliationEventEmitter;

    @Transactional
    public OrderLifecycleResult reconcileOrder(TradeOrderEntity order) {
        return reconcileOrder(order, OrderReconciliationSource.AUTO_WORKER);
    }

    @Transactional
    public OrderLifecycleResult reconcileOrder(TradeOrderEntity order, OrderReconciliationSource source) {
        OrderReconciliationResult result = reconcileOrderDetailed(order, source);
        return OrderLifecycleResult.of(
                tradeRepository.findById(order.getTradeId()).orElse(null),
                order,
                result.remoteStatusSuccess() || result.remoteFillsSuccess(),
                result.resolvedStatus().name()
        );
    }

    @Transactional
    public OrderReconciliationResult reconcileOrderDetailed(TradeOrderEntity order, OrderReconciliationSource source) {
        OrderReconciliationSource resolvedSource = source == null ? OrderReconciliationSource.AUTO_WORKER : source;
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        OrderReconciliationBeforeState before = OrderReconciliationBeforeState.from(order);
        reconciliationEventEmitter.emitRequested(resolvedSource, order, before, "reconcile order");

        ExecutorOrderStatusResponse remoteStatus = order.getRemoteOrderId() == null
                ? ExecutorOrderStatusResponse.failure(null, "UNKNOWN_RESPONSE", "order has no remoteOrderId")
                : liveExecutionService.fetchRemoteOrderStatus(order.getRemoteOrderId());
        ExecutorFillsResponse fills = liveExecutionService.fetchRemoteFills(
                order.getRemoteOrderId(),
                order.getMarketId(),
                order.getTokenId(),
                order.getSide(),
                order.getRequestedPrice(),
                order.getRequestedShares(),
                fillLookupSince(order)
        );
        Instant pulledAt = TimeMachine.now();

        List<ImportedFill> importedFills = List.of();
        if (fills.success()) {
            importedFills = saveNewFills(order, fills);
        }
        reconciliationEventEmitter.emitPulled(resolvedSource, order, before, remoteStatus, fills, importedFills.size(), "remote pull completed", pulledAt);
        for (ImportedFill importedFill : importedFills) {
            reconciliationEventEmitter.emitFillImported(
                    resolvedSource,
                    order,
                    before,
                    importedFill.entity(),
                    importedFill.remote(),
                    remoteStatus,
                    fills,
                    "new remote fill imported",
                    pulledAt
            );
        }

        TradeOrderStatus status = resolveStatus(remoteStatus, fills, order);
        applyOrderState(order, status, remoteStatus, fills);
        reconcileTradeAfterOrderState(trade, order);
        emitCancellationIfNew(trade, order, before.status(), status, remoteStatus);
        logReconciliation(order, remoteStatus, fills, status);
        order.markReconciled();
        tradeOrderRepository.save(order);
        if (trade != null) {
            tradeRepository.save(trade);
        }
        if (!remoteStatus.success() && !fills.success()) {
            reconciliationEventEmitter.emitFailed(resolvedSource, order, before, remoteStatus, fills, "remote status and fills both failed", pulledAt);
        } else if (before.status() != order.getStatus()) {
            reconciliationEventEmitter.emitStatusChanged(resolvedSource, order, before, order.getStatus(), remoteStatus, fills, importedFills.size(), "local order status changed", pulledAt);
        } else {
            reconciliationEventEmitter.emitNoChange(resolvedSource, order, before, remoteStatus, fills, importedFills.size(), "local order status unchanged", pulledAt);
        }
        return OrderReconciliationResult.from(
                order,
                before.status(),
                remoteStatus.success(),
                fills.success(),
                remoteStatus.status(),
                fills.fills().size(),
                importedFills.size(),
                warnings(remoteStatus, fills)
        );
    }

    @Transactional
    public int reconcileOpenOrders() {
        return reconcileOpenOrders(OrderReconciliationSource.AUTO_WORKER);
    }

    @Transactional
    public int reconcileOpenOrders(OrderReconciliationSource source) {
        List<TradeOrderStatus> activeStatuses = List.of(
                TradeOrderStatus.CREATED,
                TradeOrderStatus.SUBMITTING,
                TradeOrderStatus.SUBMITTED,
                TradeOrderStatus.RESTING,
                TradeOrderStatus.PARTIALLY_FILLED,
                TradeOrderStatus.CANCEL_REQUESTED,
                TradeOrderStatus.UNKNOWN
        );
        List<TradeOrderEntity> orders = tradeOrderRepository.findReconcilableRemoteOrders(activeStatuses);
        orders.forEach(order -> reconcileOrder(order, source));
        return orders.size();
    }

    @Transactional
    public OrderLifecycleResult reconcileOrder(String localOrRemoteOrderId, OrderReconciliationSource source) {
        TradeOrderEntity order = tradeOrderRepository.findByLocalOrderId(localOrRemoteOrderId)
                .or(() -> tradeOrderRepository.findByClientOrderId(localOrRemoteOrderId))
                .or(() -> tradeOrderRepository.findByRemoteOrderId(localOrRemoteOrderId))
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + localOrRemoteOrderId));
        return reconcileOrder(order, source);
    }

    @Transactional
    public OrderLifecycleResult reconcileOrder(String localOrRemoteOrderId) {
        return reconcileOrder(localOrRemoteOrderId, OrderReconciliationSource.AUTO_WORKER);
    }

    @Transactional
    public void applyImmediateFill(TradeEntity trade, TradeOrderEntity order, ExecutorOrderResponse response) {
        BigDecimal fee = response.feeUsd();
        boolean feeKnown = response.feeUsd() != null;
        order.applyFillState(
                TradeOrderStatus.FILLED,
                response.averagePrice(),
                zeroIfNull(response.filledShares()),
                zeroIfNull(response.filledAmountUsd()),
                BigDecimal.ZERO,
                fee,
                feeKnown,
                order.getOrderType().expectedLiquidityRole(),
                response.rawResponse()
        );
        tradeOrderRepository.save(order);
        if (order.getPhase() == TradeOrderPhase.ENTRY) {
            trade.markOpen(response.averagePrice(), response.filledShares(), response.filledAmountUsd(), fee, response.exchangeTimestamp());
        } else {
            trade.markClosed(response.averagePrice(), response.filledShares(), response.filledAmountUsd(), fee, response.exchangeTimestamp());
        }
        tradeRepository.save(trade);
    }

    @Transactional
    public void reconcileTradeAfterOrderState(TradeEntity trade, TradeOrderEntity order) {
        if (trade == null) {
            return;
        }
        if (order.getPhase() == TradeOrderPhase.ENTRY) {
            reconcileEntryTrade(trade, order);
        } else {
            reconcileExitTrade(trade, order);
        }
    }

    private List<ImportedFill> saveNewFills(TradeOrderEntity order, ExecutorFillsResponse fills) {
        List<ImportedFill> imported = new ArrayList<>();
        for (ExecutorFillResponse fill : fills.fills()) {
            String fillId = remoteFillId(fill);
            if (fillId != null && tradeFillRepository.findByRemoteFillId(fillId).isPresent()) {
                continue;
            }
            String remoteOrderId = fill.remoteOrderId() == null ? order.getRemoteOrderId() : fill.remoteOrderId();
            String marketId = fill.marketId() == null ? order.getMarketId() : fill.marketId();
            String tokenId = fill.tokenId() == null ? order.getTokenId() : fill.tokenId();
            TradeSide side = fill.side() == null ? order.getSide() : fill.side();
            String remoteFillKey = TradeFillEntity.remoteFillKey(
                    remoteOrderId,
                    order.getTradeId(),
                    fillId,
                    marketId,
                    tokenId,
                    side,
                    fill.price(),
                    fill.shares(),
                    fill.timestamp()
            );
            if (tradeFillRepository.findByRemoteFillKey(remoteFillKey).isPresent()) {
                continue;
            }
            TradeFillEntity entity = tradeFillRepository.save(TradeFillEntity.remote(
                    order.getTradeId(),
                    order.getId(),
                    remoteOrderId,
                    fillId,
                    marketId,
                    tokenId,
                    side,
                    fill.price(),
                    fill.shares(),
                    fill.fee(),
                    fill.role() == null ? LiquidityRole.UNKNOWN.name() : fill.role().name(),
                    fill.timestamp(),
                    fill.rawResponse()
            ));
            imported.add(new ImportedFill(entity, fill));
        }
        return imported;
    }

    private TradeOrderStatus resolveStatus(ExecutorOrderStatusResponse remoteStatus, ExecutorFillsResponse fills, TradeOrderEntity order) {
        BigDecimal filled = aggregate(order).filledShares();
        if (filled.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal requested = requestedShares(order);
            return requested != null && filled.compareTo(requested) >= 0
                    ? TradeOrderStatus.FILLED
                    : TradeOrderStatus.PARTIALLY_FILLED;
        }
        if (remoteStatus.success()) {
            BigDecimal remoteFilled = remoteStatus.filledSize();
            if (remoteFilled != null && remoteFilled.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal requested = requestedShares(order);
                if (requested == null) {
                    requested = remoteStatus.originalSize();
                }
                return requested != null && remoteFilled.compareTo(requested) >= 0
                        ? TradeOrderStatus.FILLED
                        : TradeOrderStatus.PARTIALLY_FILLED;
            }
            if (remoteStatus.lifecycleStatus() == TradeOrderStatus.FILLED) {
                return TradeOrderStatus.FILLED;
            }
        }
        if (isStickyTerminal(order.getStatus())) {
            return order.getStatus();
        }
        if (remoteStatus.success()) {
            TradeOrderStatus remoteLifecycle = remoteStatus.lifecycleStatus();
            if (remoteLifecycle == TradeOrderStatus.CANCELLED
                    || remoteLifecycle == TradeOrderStatus.EXPIRED
                    || remoteLifecycle == TradeOrderStatus.REJECTED
                    || remoteLifecycle == TradeOrderStatus.FAILED) {
                return remoteLifecycle;
            }
            if (remoteLifecycle == TradeOrderStatus.SUBMITTED
                    || remoteLifecycle == TradeOrderStatus.RESTING
                    || remoteLifecycle == TradeOrderStatus.OPEN) {
                return TradeOrderStatus.RESTING;
            }
        }
        if (isExpiredUnfilledGtd(order, remoteStatus, fills)) {
            return TradeOrderStatus.EXPIRED;
        }
        return TradeOrderStatus.UNKNOWN;
    }

    private boolean isExpiredUnfilledGtd(
            TradeOrderEntity order,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills
    ) {
        if (isStickyTerminal(order.getStatus()) || !order.getStatus().isActive()) {
            return false;
        }
        if (order.getOrderType() != TradeOrderType.GTD || !fills.success() || !fills.fills().isEmpty()) {
            return false;
        }
        if (remoteStatus.success() && remoteStatus.lifecycleStatus() != TradeOrderStatus.UNKNOWN) {
            return false;
        }
        Instant submittedAt = order.getSubmittedAt();
        if (submittedAt == null) {
            return false;
        }
        Instant staleAfter = submittedAt.plus(Duration.ofMinutes(properties.getMaxReconcileAgeMinutes()));
        return !TimeMachine.now().isBefore(staleAfter);
    }

    private Instant fillLookupSince(TradeOrderEntity order) {
        Instant submittedAt = order.getSubmittedAt();
        if (submittedAt == null) {
            return null;
        }
        long lookbackSeconds = Math.max(0, properties.getFillLookupLookbackSeconds());
        return submittedAt.minusSeconds(lookbackSeconds);
    }

    private void applyOrderState(
            TradeOrderEntity order,
            TradeOrderStatus status,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills
    ) {
        FillTotals totals = aggregate(order);
        boolean remoteFillEvidence = hasRemoteFillEvidence(remoteStatus);
        boolean pulledFillEvidence = fills.success() && !fills.fills().isEmpty();
        if (status == order.getStatus()
                && isStickyTerminal(status)
                && !remoteFillEvidence
                && !pulledFillEvidence) {
            return;
        }
        BigDecimal filledShares = totals.filledShares();
        BigDecimal filledAmountUsd = totals.filledAmountUsd();
        BigDecimal avgPrice = totals.avgPrice();
        if (filledShares.compareTo(BigDecimal.ZERO) == 0 && remoteStatus.filledSize() != null) {
            filledShares = remoteStatus.filledSize();
            avgPrice = remoteStatus.avgFillPrice() != null ? remoteStatus.avgFillPrice() : remoteStatus.price();
            if (avgPrice != null) {
                filledAmountUsd = avgPrice.multiply(filledShares);
            }
        } else if (filledShares.compareTo(BigDecimal.ZERO) == 0
                && status == TradeOrderStatus.FILLED
                && remoteStatus.originalSize() != null) {
            filledShares = remoteStatus.originalSize();
            avgPrice = remoteStatus.avgFillPrice() != null ? remoteStatus.avgFillPrice() : remoteStatus.price();
            if (avgPrice != null) {
                filledAmountUsd = avgPrice.multiply(filledShares);
            }
        }
        BigDecimal requested = requestedShares(order);
        BigDecimal remaining = remoteStatus.remainingSize();
        if (remaining == null && requested != null) {
            remaining = requested.subtract(filledShares).max(BigDecimal.ZERO);
        }
        if (avgPrice == null) {
            avgPrice = remoteStatus.avgFillPrice() != null ? remoteStatus.avgFillPrice() : remoteStatus.price();
        }
        LiquidityRole role = totals.role() == null ? LiquidityRole.UNKNOWN : totals.role();

        switch (status) {
            case FILLED, PARTIALLY_FILLED -> order.applyFillState(
                    status,
                    avgPrice,
                    filledShares,
                    filledAmountUsd,
                    remaining,
                    totals.feeUsd(),
                    totals.feeKnown(),
                    role,
                    usefulRaw(remoteStatus.rawResponse(), fills.rawResponse())
            );
            case RESTING, SUBMITTED -> order.markResting(usefulRaw(remoteStatus.rawResponse()));
            case CANCELLED -> order.markCancelled(firstNonBlank(order.getCancelReason(), "remote cancelled"), usefulRaw(remoteStatus.rawResponse()));
            case EXPIRED -> order.markExpired(usefulRaw(remoteStatus.rawResponse()));
            case REJECTED -> order.markRejected(errorMessage(remoteStatus), usefulRaw(remoteStatus.rawResponse()));
            case FAILED -> order.markFailed(errorMessage(remoteStatus), usefulRaw(remoteStatus.rawResponse()));
            default -> order.markUnknown(usefulRaw(remoteStatus.rawResponse(), fills.rawResponse()));
        }
    }

    private void reconcileEntryTrade(TradeEntity trade, TradeOrderEntity order) {
        if (order.getStatus() == TradeOrderStatus.FILLED) {
            trade.markOpen(order.getAvgFillPrice(), order.getFilledShares(), order.getFilledAmountUsd(), order.getRealizedFeeUsd(), order.getCompletedAt());
        } else if (order.getStatus() == TradeOrderStatus.PARTIALLY_FILLED) {
            trade.markPartiallyOpen(order.getAvgFillPrice(), order.getFilledShares(), order.getFilledAmountUsd(), order.getRealizedFeeUsd(), order.getCompletedAt());
        } else if (order.getStatus() == TradeOrderStatus.CANCELLED) {
            if (zeroIfNull(order.getFilledShares()).compareTo(BigDecimal.ZERO) == 0) {
                trade.markCancelled();
            }
        } else if (order.getStatus() == TradeOrderStatus.EXPIRED) {
            return;
        } else if (order.getStatus() == TradeOrderStatus.REJECTED || order.getStatus() == TradeOrderStatus.FAILED) {
            if (trade.getStatus() != null && trade.getStatus().isTerminal()) {
                return;
            }
            trade.markFailed(order.getFailureReason());
        } else if (order.getStatus() == TradeOrderStatus.RESTING || order.getStatus() == TradeOrderStatus.SUBMITTED) {
            if (trade.getStatus() != null && trade.getStatus().isTerminal()) {
                return;
            }
            trade.markEntryPending();
        }
    }

    private void reconcileExitTrade(TradeEntity trade, TradeOrderEntity order) {
        if (order.getStatus() == TradeOrderStatus.FILLED) {
            trade.markClosed(order.getAvgFillPrice(), order.getFilledShares(), order.getFilledAmountUsd(), order.getRealizedFeeUsd(), order.getCompletedAt());
        } else if (order.getStatus() == TradeOrderStatus.PARTIALLY_FILLED) {
            trade.markPartiallyClosed(order.getAvgFillPrice(), order.getFilledShares(), order.getFilledAmountUsd(), order.getRealizedFeeUsd(), order.getCompletedAt());
        } else if (order.getStatus() == TradeOrderStatus.RESTING || order.getStatus() == TradeOrderStatus.SUBMITTED) {
            trade.markExitPending();
        }
    }

    private void emitCancellationIfNew(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            ExecutorOrderStatusResponse remoteStatus
    ) {
        if (resolvedStatus != TradeOrderStatus.CANCELLED || previousStatus == TradeOrderStatus.CANCELLED) {
            return;
        }
        cancellationEventEmitter.emitCancelled(
                trade,
                order,
                previousStatus,
                resolvedStatus,
                order.getCancelReason(),
                remoteStatus.rawResponse()
        );
    }

    private void logReconciliation(
            TradeOrderEntity order,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            TradeOrderStatus resolvedStatus
    ) {
        log.info(
                "Order reconciliation result orderId={} tradeId={} remoteOrderId={} phase={} localStatus={} resolvedStatus={} remoteSuccess={} remoteStatus={} remoteError={} remoteFilledSize={} remoteOriginalSize={} remoteRemainingSize={} remoteAvgPrice={} fillsSuccess={} fillsError={} fillsCount={} filledShares={} avgFillPrice={} remainingShares={}",
                order.getId(),
                order.getTradeId(),
                order.getRemoteOrderId(),
                order.getPhase(),
                order.getStatus(),
                resolvedStatus,
                remoteStatus.success(),
                remoteStatus.status(),
                remoteStatus.error() == null ? null : remoteStatus.error().message(),
                remoteStatus.filledSize(),
                remoteStatus.originalSize(),
                remoteStatus.remainingSize(),
                remoteStatus.avgFillPrice(),
                fills.success(),
                fills.error() == null ? null : fills.error().message(),
                fills.fills().size(),
                order.getFilledShares(),
                order.getAvgFillPrice(),
                order.getRemainingShares()
        );
    }

    private FillTotals aggregate(TradeOrderEntity order) {
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal amountUsd = BigDecimal.ZERO;
        BigDecimal feeUsd = BigDecimal.ZERO;
        boolean feeKnown = true;
        LiquidityRole role = null;
        for (TradeFillEntity fill : tradeFillRepository.findByOrderId(order.getId())) {
            BigDecimal fillShares = zeroIfNull(fill.getShares());
            shares = shares.add(fillShares);
            amountUsd = amountUsd.add(fill.getAmountUsd() == null && fill.getPrice() != null
                    ? fill.getPrice().multiply(fillShares)
                    : zeroIfNull(fill.getAmountUsd()));
            if (Boolean.TRUE.equals(fill.getFeeKnown())) {
                feeUsd = feeUsd.add(zeroIfNull(fill.getFeeUsd()));
            } else {
                feeKnown = false;
            }
            role = mergeRole(role, fill.getLiquidityRole());
        }
        BigDecimal avgPrice = shares.compareTo(BigDecimal.ZERO) > 0
                ? amountUsd.divide(shares, 8, RoundingMode.HALF_UP)
                : null;
        return new FillTotals(shares, amountUsd, feeKnown ? feeUsd : null, feeKnown, avgPrice, role);
    }

    private LiquidityRole mergeRole(LiquidityRole current, String next) {
        LiquidityRole parsed;
        try {
            parsed = next == null ? LiquidityRole.UNKNOWN : LiquidityRole.valueOf(next);
        } catch (IllegalArgumentException ignored) {
            parsed = LiquidityRole.UNKNOWN;
        }
        if (current == null || current == LiquidityRole.UNKNOWN) {
            return parsed;
        }
        return current == parsed ? current : LiquidityRole.UNKNOWN;
    }

    private String remoteFillId(ExecutorFillResponse fill) {
        if (fill.fillId() != null && !fill.fillId().isBlank()) {
            return fill.fillId();
        }
        if (fill.tradeId() != null && !fill.tradeId().isBlank()) {
            return fill.tradeId();
        }
        return null;
    }

    private BigDecimal requestedShares(TradeOrderEntity order) {
        if (order.getRequestedShares() != null) {
            return order.getRequestedShares();
        }
        if (order.getRequestedAmountUsd() != null && order.getRequestedPrice() != null) {
            return order.getRequestedAmountUsd().divide(order.getRequestedPrice(), 8, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String errorMessage(ExecutorOrderStatusResponse response) {
        return response.error() == null ? response.status() : response.error().message();
    }

    private boolean isStickyTerminal(TradeOrderStatus status) {
        return status == TradeOrderStatus.CANCELLED
                || status == TradeOrderStatus.FILLED
                || status == TradeOrderStatus.REJECTED
                || status == TradeOrderStatus.FAILED
                || status == TradeOrderStatus.EXPIRED;
    }

    private boolean hasRemoteFillEvidence(ExecutorOrderStatusResponse remoteStatus) {
        if (remoteStatus == null || !remoteStatus.success()) {
            return false;
        }
        return (remoteStatus.filledSize() != null && remoteStatus.filledSize().compareTo(BigDecimal.ZERO) > 0)
                || remoteStatus.lifecycleStatus() == TradeOrderStatus.FILLED;
    }

    private String usefulRaw(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (isUsefulRaw(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isUsefulRaw(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private List<String> warnings(ExecutorOrderStatusResponse remoteStatus, ExecutorFillsResponse fills) {
        List<String> warnings = new ArrayList<>();
        if (!remoteStatus.success() && remoteStatus.error() != null) {
            warnings.add("remote status failed: " + remoteStatus.error().message());
        }
        if (!fills.success() && fills.error() != null) {
            warnings.add("remote fills failed: " + fills.error().message());
        }
        return warnings;
    }

    private record ImportedFill(TradeFillEntity entity, ExecutorFillResponse remote) {
    }

    private record FillTotals(
            BigDecimal filledShares,
            BigDecimal filledAmountUsd,
            BigDecimal feeUsd,
            boolean feeKnown,
            BigDecimal avgPrice,
            LiquidityRole role
    ) {
    }
}
