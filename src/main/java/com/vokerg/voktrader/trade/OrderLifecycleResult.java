package com.vokerg.voktrader.trade;

public record OrderLifecycleResult(
        boolean success,
        Long tradeId,
        Long orderId,
        String localOrderId,
        String remoteOrderId,
        TradeStatus tradeStatus,
        TradeOrderStatus orderStatus,
        String message
) {
    public static OrderLifecycleResult of(TradeEntity trade, TradeOrderEntity order, boolean success, String message) {
        return new OrderLifecycleResult(
                success,
                trade == null ? null : trade.getId(),
                order == null ? null : order.getId(),
                order == null ? null : order.getLocalOrderId(),
                order == null ? null : order.getRemoteOrderId(),
                trade == null ? null : trade.getStatus(),
                order == null ? null : order.getStatus(),
                message
        );
    }
}
