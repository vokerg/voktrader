package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationEventEmitter {
    public static final String CANCEL_REQUESTED_EVENT = "LIVE_ORDER_CANCEL_REQUESTED";
    public static final String CANCEL_STATE_CHANGED_EVENT = "LIVE_ORDER_CANCEL_STATE_CHANGED";
    public static final String CANCELLED_EVENT = "LIVE_ORDER_CANCELLED";
    public static final String PAPER_CANCEL_REQUESTED_EVENT = "PAPER_ORDER_CANCEL_REQUESTED";
    public static final String PAPER_CANCELLED_EVENT = "PAPER_ORDER_CANCELLED";

    private final TradeEventRepository tradeEventRepository;
    private final ObjectMapper objectMapper;

    public void emitCancelRequested(
            TradeEntity trade,
            TradeOrderEntity order,
            String reason,
            String rawResponse
    ) {
        emitOnce(
                CANCEL_REQUESTED_EVENT,
                "live order cancel requested",
                trade,
                order,
                order == null ? null : order.getStatus(),
                order == null ? null : order.getStatus(),
                reason,
                rawResponse
        );
    }

    public void emitCancelStateChanged(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            String reason,
            String rawResponse
    ) {
        tradeEventRepository.save(TradeEventEntity.of(
                order == null ? null : order.getTradeId(),
                order == null ? null : order.getId(),
                null,
                CANCEL_STATE_CHANGED_EVENT,
                "live order cancel state changed",
                payloadJson(
                        trade,
                        order,
                        previousStatus,
                        order == null ? null : order.getStatus(),
                        reason,
                        rawResponse
                )
        ));
    }

    public void emitCancelled(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            String reason,
            String rawResponse
    ) {
        emitOnce(
                CANCELLED_EVENT,
                "live order cancelled",
                trade,
                order,
                previousStatus,
                resolvedStatus,
                reason,
                rawResponse
        );
    }

    public void emitPaperCancelRequested(
            TradeEntity trade,
            TradeOrderEntity order,
            String reason,
            String rawResponse
    ) {
        emitOnce(
                PAPER_CANCEL_REQUESTED_EVENT,
                "paper order cancel requested",
                trade,
                order,
                order == null ? null : order.getStatus(),
                order == null ? null : order.getStatus(),
                reason,
                rawResponse
        );
    }

    public void emitPaperCancelled(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            String reason,
            String rawResponse
    ) {
        emitOnce(
                PAPER_CANCELLED_EVENT,
                "paper order cancelled",
                trade,
                order,
                previousStatus,
                resolvedStatus,
                reason,
                rawResponse
        );
    }

    private void emitOnce(
            String eventType,
            String message,
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            String reason,
            String rawResponse
    ) {
        if (order != null
                && order.getId() != null
                && tradeEventRepository.existsByTradeOrderIdAndEventType(order.getId(), eventType)) {
            return;
        }
        tradeEventRepository.save(TradeEventEntity.of(
                order == null ? null : order.getTradeId(),
                order == null ? null : order.getId(),
                null,
                eventType,
                message,
                payloadJson(trade, order, previousStatus, resolvedStatus, reason, rawResponse)
        ));
    }

    private String payloadJson(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            String reason,
            String rawResponse
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tradeId", order == null ? null : order.getTradeId());
        payload.put("orderId", order == null ? null : order.getId());
        payload.put("side", order == null || order.getSide() == null ? null : order.getSide().name());
        payload.put("phase", order == null || order.getPhase() == null ? null : order.getPhase().name());
        payload.put("strategyId", order == null ? null : order.getStrategyId());
        payload.put("ruleId", order == null ? null : order.getRuleId());
        payload.put("botId", order == null ? null : order.getBotId());
        payload.put("localOrderId", order == null ? null : order.getLocalOrderId());
        payload.put("remoteOrderId", order == null ? null : order.getRemoteOrderId());
        payload.put("exchangeOrderId", order == null ? null : order.getExchangeOrderId());
        payload.put("previousStatus", previousStatus == null ? null : previousStatus.name());
        payload.put("resolvedStatus", resolvedStatus == null ? null : resolvedStatus.name());
        payload.put("tradeStatus", trade == null || trade.getStatus() == null ? null : trade.getStatus().name());
        payload.put("cancelReason", reason);
        payload.put("submittedAt", order == null ? null : order.getSubmittedAt());
        payload.put("completedAt", order == null ? null : order.getCompletedAt());
        payload.put("lifetimeMs", lifetimeMs(order));
        payload.put("remoteStatusRaw", rawResponse);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize order cancellation event payload tradeId={} orderId={}",
                    order == null ? null : order.getTradeId(),
                    order == null ? null : order.getId(),
                    e);
            return null;
        }
    }

    private Long lifetimeMs(TradeOrderEntity order) {
        if (order == null || order.getSubmittedAt() == null || order.getCompletedAt() == null) {
            return null;
        }
        return Duration.between(order.getSubmittedAt(), order.getCompletedAt()).toMillis();
    }
}
