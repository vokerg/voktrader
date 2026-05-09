package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.trade.TradeOrderStatus;
import com.vokerg.voktrader.trade.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutorOrderStatusResponse(
        boolean success,
        String remoteOrderId,
        String status,
        String marketId,
        String tokenId,
        TradeSide side,
        BigDecimal price,
        BigDecimal originalSize,
        BigDecimal filledSize,
        BigDecimal remainingSize,
        BigDecimal avgFillPrice,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        String rawResponse,
        ExecutorErrorResponse error
) {
    public static ExecutorOrderStatusResponse failure(String remoteOrderId, String type, String message) {
        return new ExecutorOrderStatusResponse(
                false,
                remoteOrderId,
                "UNKNOWN",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ExecutorErrorResponse(type, message)
        );
    }

    public TradeOrderStatus lifecycleStatus() {
        return ExecutorOrderStatusMapper.toLifecycleStatus(status);
    }
}
