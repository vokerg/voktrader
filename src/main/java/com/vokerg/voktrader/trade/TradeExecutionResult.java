package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeStatus;

public record TradeExecutionResult(
        boolean accepted,
        ExecutionMode mode,
        Long tradeId,
        Long orderId,
        TradeStatus tradeStatus,
        TradeOrderStatus orderStatus,
        String message,
        String localOrderId,
        String remoteOrderId,
        String error
) {
    public static TradeExecutionResult accepted(ExecutionMode mode, Long tradeId, Long orderId, TradeStatus tradeStatus, TradeOrderStatus orderStatus, String message) {
        return new TradeExecutionResult(true, mode, tradeId, orderId, tradeStatus, orderStatus, message, null, null, null);
    }

    public static TradeExecutionResult rejected(ExecutionMode mode, Long tradeId, Long orderId, TradeStatus tradeStatus, TradeOrderStatus orderStatus, String message) {
        return new TradeExecutionResult(false, mode, tradeId, orderId, tradeStatus, orderStatus, message, null, null, message);
    }

    public static TradeExecutionResult fromOrderLifecycle(ExecutionMode mode, OrderLifecycleResult result) {
        return new TradeExecutionResult(
                result.success(),
                mode,
                result.tradeId(),
                result.orderId(),
                result.tradeStatus(),
                result.orderStatus(),
                result.message(),
                result.localOrderId(),
                result.remoteOrderId(),
                result.error()
        );
    }
}
