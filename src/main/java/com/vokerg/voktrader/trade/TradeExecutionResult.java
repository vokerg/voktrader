package com.vokerg.voktrader.trade;

public record TradeExecutionResult(
        boolean accepted,
        ExecutionMode mode,
        Long tradeId,
        Long orderId,
        TradeStatus tradeStatus,
        TradeOrderStatus orderStatus,
        String message
) {
    public static TradeExecutionResult accepted(ExecutionMode mode, Long tradeId, Long orderId, TradeStatus tradeStatus, TradeOrderStatus orderStatus, String message) {
        return new TradeExecutionResult(true, mode, tradeId, orderId, tradeStatus, orderStatus, message);
    }

    public static TradeExecutionResult rejected(ExecutionMode mode, Long tradeId, Long orderId, TradeStatus tradeStatus, TradeOrderStatus orderStatus, String message) {
        return new TradeExecutionResult(false, mode, tradeId, orderId, tradeStatus, orderStatus, message);
    }
}
