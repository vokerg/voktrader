package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderReconciliationService {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final LiveExecutionService liveExecutionService;

    @Transactional
    public OrderLifecycleResult reconcileOrder(TradeOrderEntity order) {
        TradeEntity trade = tradeRepository.findById(order.getTradeId()).orElse(null);
        ExecutorOrderStatusResponse remoteStatus = order.getRemoteOrderId() == null
                ? ExecutorOrderStatusResponse.failure(null, "UNKNOWN_RESPONSE", "order has no remoteOrderId")
                : liveExecutionService.fetchRemoteOrderStatus(order.getRemoteOrderId());
        ExecutorFillsResponse fills = liveExecutionService.fetchRemoteFills(
                order.getRemoteOrderId(),
                order.getMarketId(),
                order.getTokenId(),
                order.getSubmittedAt()
        );

        if (fills.success()) {
            saveNewFills(order, fills);
        }

        TradeOrderStatus status = resolveStatus(remoteStatus, fills, order);
        applyOrderState(order, status, remoteStatus, fills);
        reconcileTradeAfterOrderState(trade, order);
        order.markReconciled();
        tradeOrderRepository.save(order);
        if (trade != null) {
            tradeRepository.save(trade);
        }
        return OrderLifecycleResult.of(trade, order, remoteStatus.success() || fills.success(), status.name());
    }

    @Transactional
    public int reconcileOpenOrders() {
        List<TradeOrderStatus> activeStatuses = List.of(
                TradeOrderStatus.CREATED,
                TradeOrderStatus.SUBMITTING,
                TradeOrderStatus.SUBMITTED,
                TradeOrderStatus.RESTING,
                TradeOrderStatus.PARTIALLY_FILLED,
                TradeOrderStatus.CANCEL_REQUESTED,
                TradeOrderStatus.UNKNOWN
        );
        List<TradeOrderEntity> orders = tradeOrderRepository.findByStatusIn(activeStatuses);
        orders.forEach(this::reconcileOrder);
        return orders.size();
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

    private void saveNewFills(TradeOrderEntity order, ExecutorFillsResponse fills) {
        for (ExecutorFillResponse fill : fills.fills()) {
            String fillId = remoteFillId(fill);
            if (fillId != null && tradeFillRepository.findByRemoteFillId(fillId).isPresent()) {
                continue;
            }
            tradeFillRepository.save(TradeFillEntity.remote(
                    order.getTradeId(),
                    order.getId(),
                    fill.remoteOrderId() == null ? order.getRemoteOrderId() : fill.remoteOrderId(),
                    fillId,
                    fill.marketId() == null ? order.getMarketId() : fill.marketId(),
                    fill.tokenId() == null ? order.getTokenId() : fill.tokenId(),
                    fill.side() == null ? order.getSide() : fill.side(),
                    fill.price(),
                    fill.shares(),
                    fill.fee(),
                    fill.role() == null ? LiquidityRole.UNKNOWN.name() : fill.role().name(),
                    fill.timestamp(),
                    fill.rawResponse()
            ));
        }
    }

    private TradeOrderStatus resolveStatus(ExecutorOrderStatusResponse remoteStatus, ExecutorFillsResponse fills, TradeOrderEntity order) {
        BigDecimal filled = aggregate(order).filledShares();
        if (remoteStatus.success() && remoteStatus.lifecycleStatus() != TradeOrderStatus.UNKNOWN) {
            return remoteStatus.lifecycleStatus() == TradeOrderStatus.SUBMITTED ? TradeOrderStatus.RESTING : remoteStatus.lifecycleStatus();
        }
        if (filled.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal requested = requestedShares(order);
            return requested != null && filled.compareTo(requested) >= 0
                    ? TradeOrderStatus.FILLED
                    : TradeOrderStatus.PARTIALLY_FILLED;
        }
        if (fills.success()) {
            return TradeOrderStatus.UNKNOWN;
        }
        return TradeOrderStatus.UNKNOWN;
    }

    private void applyOrderState(
            TradeOrderEntity order,
            TradeOrderStatus status,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills
    ) {
        FillTotals totals = aggregate(order);
        BigDecimal filledShares = totals.filledShares();
        BigDecimal filledAmountUsd = totals.filledAmountUsd();
        BigDecimal avgPrice = totals.avgPrice();
        if (filledShares.compareTo(BigDecimal.ZERO) == 0 && remoteStatus.filledSize() != null) {
            filledShares = remoteStatus.filledSize();
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
                    coalesce(remoteStatus.rawResponse(), fills.rawResponse())
            );
            case RESTING, SUBMITTED -> order.markResting(remoteStatus.rawResponse());
            case CANCELLED -> order.markCancelled("remote cancelled", remoteStatus.rawResponse());
            case EXPIRED -> order.markExpired(remoteStatus.rawResponse());
            case REJECTED -> order.markRejected(errorMessage(remoteStatus), remoteStatus.rawResponse());
            case FAILED -> order.markFailed(errorMessage(remoteStatus), remoteStatus.rawResponse());
            default -> order.markUnknown(coalesce(remoteStatus.rawResponse(), fills.rawResponse()));
        }
    }

    private void reconcileEntryTrade(TradeEntity trade, TradeOrderEntity order) {
        if (order.getStatus() == TradeOrderStatus.FILLED) {
            trade.markOpen(order.getAvgFillPrice(), order.getFilledShares(), order.getFilledAmountUsd(), order.getRealizedFeeUsd(), order.getCompletedAt());
        } else if (order.getStatus() == TradeOrderStatus.PARTIALLY_FILLED) {
            trade.markPartiallyOpen(order.getAvgFillPrice(), order.getFilledShares(), order.getFilledAmountUsd(), order.getRealizedFeeUsd(), order.getCompletedAt());
        } else if (order.getStatus() == TradeOrderStatus.CANCELLED || order.getStatus() == TradeOrderStatus.EXPIRED) {
            if (zeroIfNull(order.getFilledShares()).compareTo(BigDecimal.ZERO) == 0) {
                trade.markCancelled();
            }
        } else if (order.getStatus() == TradeOrderStatus.REJECTED || order.getStatus() == TradeOrderStatus.FAILED) {
            trade.markFailed(order.getFailureReason());
        } else if (order.getStatus() == TradeOrderStatus.RESTING || order.getStatus() == TradeOrderStatus.SUBMITTED) {
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

    private String coalesce(String first, String second) {
        return first != null ? first : second;
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
