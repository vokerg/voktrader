package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.economy.LiquidityRole;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record OrderRuntimeState(
        Long orderId,
        String localOrderId,
        String remoteOrderId,
        TradeOrderPhase phase,
        TradeOrderStatus status,
        BigDecimal requestedPrice,
        BigDecimal requestedShares,
        BigDecimal filledShares,
        BigDecimal remainingShares,
        BigDecimal avgFillPrice,
        BigDecimal realizedFeeUsd,
        Boolean feeKnown,
        LiquidityRole fillRole,
        Instant createdAt,
        Instant submittedAt,
        Instant lastReconciledAt,
        Instant updatedAt,
        String lastFailureReason
) {
    public static OrderRuntimeState from(TradeOrderEntity order) {
        if (order == null) {
            return null;
        }
        return new OrderRuntimeState(
                order.getId(),
                order.getLocalOrderId(),
                order.getRemoteOrderId(),
                order.getPhase(),
                order.getStatus(),
                order.getRequestedPrice(),
                order.getRequestedShares(),
                order.getFilledShares(),
                order.getRemainingShares(),
                order.getAvgFillPrice(),
                order.getRealizedFeeUsd(),
                order.getFeeKnown(),
                order.getFillRole(),
                order.getCreatedAt(),
                order.getSubmittedAt(),
                order.getLastReconciledAt(),
                order.getUpdatedAt(),
                firstNonBlank(order.getRejectionReason(), order.getRejectReason(), order.getFailureReason(), order.getErrorMessage(), order.getCancelReason())
        );
    }

    public Long ageSeconds(Instant now) {
        Instant start = submittedAt == null ? createdAt : submittedAt;
        if (start == null || now == null) {
            return null;
        }
        return Duration.between(start, now).toSeconds();
    }

    public String cancelIdentifier() {
        if (localOrderId != null && !localOrderId.isBlank()) {
            return localOrderId;
        }
        return remoteOrderId;
    }

    public boolean isActive() {
        return status != null && status.isActive();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
