package com.vokerg.voktrader.trade.paper;

import com.vokerg.voktrader.backtest.BacktestFillModel;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaperOrderEventEmitter {
    public static final String PAPER_ORDER_RESTING = "PAPER_ORDER_RESTING";
    public static final String PAPER_ORDER_ADVANCE_ATTEMPT = "PAPER_ORDER_ADVANCE_ATTEMPT";
    public static final String PAPER_ORDER_ADVANCE_NO_FILL = "PAPER_ORDER_ADVANCE_NO_FILL";
    public static final String PAPER_ORDER_FILLED = "PAPER_ORDER_FILLED";
    public static final String PAPER_ORDER_PARTIALLY_FILLED = "PAPER_ORDER_PARTIALLY_FILLED";
    public static final String PAPER_ORDER_EXPIRED = "PAPER_ORDER_EXPIRED";
    public static final String PAPER_ORDER_CANCEL_REQUESTED = "PAPER_ORDER_CANCEL_REQUESTED";
    public static final String PAPER_ORDER_CANCELLED = "PAPER_ORDER_CANCELLED";

    private final TradeEventRepository tradeEventRepository;
    private final ObjectMapper objectMapper;

    public void emitResting(TradeEntity trade, TradeOrderEntity order, OutcomeOrderBook book, String reason, BacktestFillModel fillModel) {
        emitOnce(PAPER_ORDER_RESTING, "paper order resting", trade, order, null, order == null ? null : order.getStatus(), book, reason, fillModel);
    }

    public void emitAdvanceAttempt(TradeEntity trade, TradeOrderEntity order, OutcomeOrderBook book, String reason, BacktestFillModel fillModel) {
        emitAlways(PAPER_ORDER_ADVANCE_ATTEMPT, "paper order advance attempt", trade, order, null, order == null ? null : order.getStatus(), book, reason, fillModel);
    }

    public void emitAdvanceNoFill(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        emitAlways(PAPER_ORDER_ADVANCE_NO_FILL, "paper order advance no fill", trade, order, previousStatus, resolvedStatus, book, reason, fillModel);
    }

    public void emitFilled(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel,
            boolean fullFill
    ) {
        emitOnce(
                fullFill ? PAPER_ORDER_FILLED : PAPER_ORDER_PARTIALLY_FILLED,
                fullFill ? "paper order filled" : "paper order partially filled",
                trade,
                order,
                previousStatus,
                order == null ? null : order.getStatus(),
                book,
                reason,
                fillModel
        );
    }

    public void emitExpired(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        emitOnce(PAPER_ORDER_EXPIRED, "paper order expired", trade, order, previousStatus, order == null ? null : order.getStatus(), book, reason, fillModel);
    }

    public void emitCancelRequested(TradeEntity trade, TradeOrderEntity order, OutcomeOrderBook book, String reason, BacktestFillModel fillModel) {
        emitOnce(PAPER_ORDER_CANCEL_REQUESTED, "paper order cancel requested", trade, order, order == null ? null : order.getStatus(), order == null ? null : order.getStatus(), book, reason, fillModel);
    }

    public void emitCancelled(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        emitOnce(PAPER_ORDER_CANCELLED, "paper order cancelled", trade, order, previousStatus, order == null ? null : order.getStatus(), book, reason, fillModel);
    }

    private void emitOnce(
            String eventType,
            String message,
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        if (order != null
                && order.getId() != null
                && tradeEventRepository.existsByTradeOrderIdAndEventType(order.getId(), eventType)) {
            return;
        }
        save(eventType, message, trade, order, previousStatus, resolvedStatus, book, reason, fillModel);
    }

    private void emitAlways(
            String eventType,
            String message,
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        save(eventType, message, trade, order, previousStatus, resolvedStatus, book, reason, fillModel);
    }

    private void save(
            String eventType,
            String message,
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        tradeEventRepository.save(TradeEventEntity.of(
                order == null ? null : order.getTradeId(),
                order == null ? null : order.getId(),
                null,
                eventType,
                message,
                payloadJson(trade, order, previousStatus, resolvedStatus, book, reason, fillModel)
        ));
    }

    private String payloadJson(
            TradeEntity trade,
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            TradeOrderStatus resolvedStatus,
            OutcomeOrderBook book,
            String reason,
            BacktestFillModel fillModel
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tradeId", order == null ? null : order.getTradeId());
        payload.put("orderId", order == null ? null : order.getId());
        payload.put("localOrderId", order == null ? null : order.getLocalOrderId());
        payload.put("mode", order == null || order.getMode() == null ? null : order.getMode().name());
        payload.put("venue", order == null || order.getVenue() == null ? null : order.getVenue().name());
        payload.put("side", order == null || order.getSide() == null ? null : order.getSide().name());
        payload.put("phase", order == null || order.getPhase() == null ? null : order.getPhase().name());
        payload.put("orderType", order == null || order.getOrderType() == null ? null : order.getOrderType().name());
        payload.put("previousStatus", previousStatus == null ? null : previousStatus.name());
        payload.put("resolvedStatus", resolvedStatus == null ? null : resolvedStatus.name());
        payload.put("strategyId", order == null ? null : order.getStrategyId());
        payload.put("ruleId", order == null ? null : order.getRuleId());
        payload.put("botId", order == null ? null : order.getBotId());
        payload.put("marketId", order == null ? null : order.getMarketId());
        payload.put("tokenId", order == null ? null : order.getTokenId());
        payload.put("tradeStatus", trade == null || trade.getStatus() == null ? null : trade.getStatus().name());
        payload.put("requestedPrice", order == null ? null : order.getRequestedPrice());
        payload.put("requestedShares", order == null ? null : order.getRequestedShares());
        payload.put("remainingShares", order == null ? null : order.getRemainingShares());
        payload.put("filledShares", order == null ? null : order.getFilledShares());
        payload.put("expiresAt", order == null ? null : order.getExpiresAt());
        payload.put("submittedAt", order == null ? null : order.getSubmittedAt());
        payload.put("completedAt", order == null ? null : order.getCompletedAt());
        payload.put("lifetimeMs", lifetimeMs(order));
        payload.put("fillModel", fillModel == null ? null : fillModel.name());
        payload.put("bestBid", price(book == null ? null : book.bestBid().orElse(null)));
        payload.put("bestAsk", price(book == null ? null : book.bestAsk().orElse(null)));
        payload.put("bestBidSize", size(book == null ? null : book.bestBid().orElse(null)));
        payload.put("bestAskSize", size(book == null ? null : book.bestAsk().orElse(null)));
        payload.put("reason", reason);
        payload.put("cancelReason", reason);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize paper order event payload tradeId={} orderId={}",
                    order == null ? null : order.getTradeId(),
                    order == null ? null : order.getId(),
                    e);
            return null;
        }
    }

    private BigDecimal price(OrderBookLevel level) {
        return level == null ? null : level.price();
    }

    private BigDecimal size(OrderBookLevel level) {
        return level == null ? null : level.size();
    }

    private Long lifetimeMs(TradeOrderEntity order) {
        if (order == null || order.getSubmittedAt() == null || order.getCompletedAt() == null) {
            return null;
        }
        return Duration.between(order.getSubmittedAt(), order.getCompletedAt()).toMillis();
    }
}
