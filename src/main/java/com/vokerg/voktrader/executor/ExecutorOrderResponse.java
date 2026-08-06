package com.vokerg.voktrader.executor;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutorOrderResponse(
        boolean accepted,
        boolean filled,
        String status,
        String exchangeOrderId,
        BigDecimal averagePrice,
        BigDecimal filledShares,
        BigDecimal filledAmountUsd,
        BigDecimal feeUsd,
        String message,
        String rawResponse,
        Instant exchangeTimestamp
) {
    public static ExecutorOrderResponse rejected(String message) {
        return new ExecutorOrderResponse(
                false,
                false,
                "REJECTED",
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                message,
                null,
                Instant.now()
        );
    }

    public boolean isDefinitiveRejection() {
        return !accepted && status != null && status.equalsIgnoreCase("REJECTED");
    }

    public String safeMessage() {
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (status != null && !status.isBlank()) {
            return status;
        }
        return accepted ? "accepted" : "rejected";
    }
}
