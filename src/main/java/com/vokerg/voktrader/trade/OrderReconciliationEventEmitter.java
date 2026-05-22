package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconciliationEventEmitter {
    public static final String REQUESTED = "ORDER_RECONCILE_REQUESTED";
    public static final String PULLED = "ORDER_RECONCILE_PULLED";
    public static final String NO_CHANGE = "ORDER_RECONCILE_NO_CHANGE";
    public static final String STATUS_CHANGED = "ORDER_RECONCILE_STATUS_CHANGED";
    public static final String FILL_IMPORTED = "ORDER_RECONCILE_FILL_IMPORTED";
    public static final String FAILED = "ORDER_RECONCILE_FAILED";
    public static final String CONFLICT = "ORDER_RECONCILE_CONFLICT";
    public static final String MANUAL_APPLIED = "ORDER_MANUAL_RECONCILE_APPLIED";

    private final TradeEventRepository tradeEventRepository;
    private final ObjectMapper objectMapper;

    public void emitRequested(OrderReconciliationSource source, TradeOrderEntity order, OrderReconciliationBeforeState before, String reason) {
        emit(REQUESTED, "order reconciliation requested", source, order, before, before == null ? null : before.status(), false,
                null, null, 0, null, null, null, null, null, null, null, reason, null);
    }

    public void emitPulled(
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            int newFillsCount,
            String reason,
            Instant pulledAt
    ) {
        emit(PULLED, "remote reconciliation state pulled", source, order, before, before == null ? null : before.status(), false,
                remoteStatus, fills, newFillsCount, null, null, null, null, null, null, null, reason, pulledAt);
    }

    public void emitNoChange(
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            int newFillsCount,
            String reason,
            Instant pulledAt
    ) {
        emit(NO_CHANGE, "order reconciliation made no status change", source, order, before, order.getStatus(), false,
                remoteStatus, fills, newFillsCount, order.getFilledShares(), order.getRemainingShares(), order.getAvgFillPrice(),
                order.getRealizedFeeUsd(), order.getFeeKnown(), null, null, reason, pulledAt);
    }

    public void emitStatusChanged(
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            TradeOrderStatus resolvedStatus,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            int newFillsCount,
            String reason,
            Instant pulledAt
    ) {
        emit(STATUS_CHANGED, "order reconciliation changed local status", source, order, before, resolvedStatus, true,
                remoteStatus, fills, newFillsCount, order.getFilledShares(), order.getRemainingShares(), order.getAvgFillPrice(),
                order.getRealizedFeeUsd(), order.getFeeKnown(), null, null, reason, pulledAt);
    }

    public void emitFillImported(
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            TradeFillEntity fill,
            ExecutorFillResponse remoteFill,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            String reason,
            Instant pulledAt
    ) {
        emit(FILL_IMPORTED, "remote fill imported during reconciliation", source, order, before, order.getStatus(), false,
                remoteStatus, fills, 1, order.getFilledShares(), order.getRemainingShares(), order.getAvgFillPrice(),
                order.getRealizedFeeUsd(), order.getFeeKnown(), fill, remoteFill, reason, pulledAt);
    }

    public void emitFailed(
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            String reason,
            Instant pulledAt
    ) {
        emit(FAILED, "order reconciliation failed to pull remote state", source, order, before, order.getStatus(), false,
                remoteStatus, fills, 0, order.getFilledShares(), order.getRemainingShares(), order.getAvgFillPrice(),
                order.getRealizedFeeUsd(), order.getFeeKnown(), null, null, reason, pulledAt);
    }

    private void emit(
            String eventType,
            String message,
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            TradeOrderStatus resolvedStatus,
            boolean statusChanged,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            int newFillsCount,
            BigDecimal filledSharesAfter,
            BigDecimal remainingSharesAfter,
            BigDecimal avgFillPriceAfter,
            BigDecimal feeUsdAfter,
            Boolean feeKnownAfter,
            TradeFillEntity importedFill,
            ExecutorFillResponse remoteFill,
            String reason,
            Instant pulledAt
    ) {
        tradeEventRepository.save(TradeEventEntity.of(
                order == null ? null : order.getTradeId(),
                order == null ? null : order.getId(),
                importedFill == null ? null : importedFill.getId(),
                eventType,
                message,
                payloadJson(source, order, before, resolvedStatus, statusChanged, remoteStatus, fills,
                        newFillsCount, filledSharesAfter, remainingSharesAfter, avgFillPriceAfter, feeUsdAfter,
                        feeKnownAfter, importedFill, remoteFill, reason, pulledAt)
        ));
    }

    private String payloadJson(
            OrderReconciliationSource source,
            TradeOrderEntity order,
            OrderReconciliationBeforeState before,
            TradeOrderStatus resolvedStatus,
            boolean statusChanged,
            ExecutorOrderStatusResponse remoteStatus,
            ExecutorFillsResponse fills,
            int newFillsCount,
            BigDecimal filledSharesAfter,
            BigDecimal remainingSharesAfter,
            BigDecimal avgFillPriceAfter,
            BigDecimal feeUsdAfter,
            Boolean feeKnownAfter,
            TradeFillEntity importedFill,
            ExecutorFillResponse remoteFill,
            String reason,
            Instant pulledAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source == null ? null : source.name());
        payload.put("tradeId", order == null ? null : order.getTradeId());
        payload.put("orderId", order == null ? null : order.getId());
        payload.put("localOrderId", order == null ? null : order.getLocalOrderId());
        payload.put("clientOrderId", order == null ? null : order.getClientOrderId());
        payload.put("idempotencyKey", order == null ? null : order.getIdempotencyKey());
        payload.put("remoteOrderId", order == null ? null : order.getRemoteOrderId());
        payload.put("exchangeOrderId", order == null ? null : order.getExchangeOrderId());
        payload.put("side", order == null || order.getSide() == null ? null : order.getSide().name());
        payload.put("phase", order == null || order.getPhase() == null ? null : order.getPhase().name());
        payload.put("strategyId", order == null ? null : order.getStrategyId());
        payload.put("ruleId", order == null ? null : order.getRuleId());
        payload.put("botId", order == null ? null : order.getBotId());
        payload.put("previousStatus", before == null || before.status() == null ? null : before.status().name());
        payload.put("resolvedStatus", resolvedStatus == null ? null : resolvedStatus.name());
        payload.put("statusChanged", statusChanged);
        payload.put("consecutiveReconcileFailures", order == null ? null : order.getConsecutiveReconcileFailures());
        payload.put("consecutiveReconcileNoProgress", order == null ? null : order.getConsecutiveReconcileNoProgress());
        payload.put("nextReconcileAt", order == null ? null : order.getNextReconcileAt());
        payload.put("reconciliationPausedAt", order == null ? null : order.getReconciliationPausedAt());
        payload.put("reconciliationPauseReason", order == null ? null : order.getReconciliationPauseReason());
        payload.put("lastReconcileProgressAt", order == null ? null : order.getLastReconcileProgressAt());
        payload.put("lastReconcileProgressSummary", order == null ? null : order.getLastReconcileProgressSummary());
        payload.put("remoteStatusSuccess", remoteStatus != null && remoteStatus.success());
        payload.put("remoteStatus", remoteStatus == null ? null : remoteStatus.status());
        payload.put("remoteError", remoteStatus == null || remoteStatus.error() == null ? null : remoteStatus.error().message());
        payload.put("remoteFilledSize", remoteStatus == null ? null : remoteStatus.filledSize());
        payload.put("remoteOriginalSize", remoteStatus == null ? null : remoteStatus.originalSize());
        payload.put("remoteRemainingSize", remoteStatus == null ? null : remoteStatus.remainingSize());
        payload.put("remoteAvgFillPrice", remoteStatus == null ? null : remoteStatus.avgFillPrice());
        payload.put("fillsSuccess", fills != null && fills.success());
        payload.put("fillsError", fills == null || fills.error() == null ? null : fills.error().message());
        payload.put("fillsCount", fills == null ? 0 : fills.fills().size());
        payload.put("newFillsCount", newFillsCount);
        payload.put("filledSharesBefore", before == null ? null : before.filledShares());
        payload.put("filledSharesAfter", filledSharesAfter);
        payload.put("remainingSharesAfter", remainingSharesAfter);
        payload.put("avgFillPriceAfter", avgFillPriceAfter);
        payload.put("feeUsdAfter", feeUsdAfter);
        payload.put("feeKnownAfter", feeKnownAfter);
        payload.put("rawRemoteStatus", remoteStatus == null ? null : remoteStatus.rawResponse());
        payload.put("rawFills", fills == null ? null : fills.rawResponse());
        payload.put("reason", reason);
        payload.put("pulledAt", pulledAt);
        if (importedFill != null || remoteFill != null) {
            payload.put("importedFillId", importedFill == null ? null : importedFill.getId());
            payload.put("remoteFillId", importedFill == null ? null : importedFill.getRemoteFillId());
            payload.put("remoteFillKey", importedFill == null ? null : importedFill.getRemoteFillKey());
            payload.put("remoteFillPrice", remoteFill == null ? null : remoteFill.price());
            payload.put("remoteFillShares", remoteFill == null ? null : remoteFill.shares());
            payload.put("remoteFillTimestamp", remoteFill == null ? null : remoteFill.timestamp());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize order reconciliation event payload orderId={}",
                    order == null ? null : order.getId(), e);
            return null;
        }
    }
}
