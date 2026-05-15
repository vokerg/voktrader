package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.trade.model.TradeOrderStatus;

public record ExecutorCancelOrderResponse(
        boolean success,
        String remoteOrderId,
        String status,
        String rawResponse,
        ExecutorErrorResponse error
) {
    public static ExecutorCancelOrderResponse failure(String remoteOrderId, String type, String message) {
        return new ExecutorCancelOrderResponse(
                false,
                remoteOrderId,
                "UNKNOWN",
                null,
                new ExecutorErrorResponse(type, message)
        );
    }

    public TradeOrderStatus lifecycleStatus() {
        return ExecutorOrderStatusMapper.toLifecycleStatus(status);
    }
}
