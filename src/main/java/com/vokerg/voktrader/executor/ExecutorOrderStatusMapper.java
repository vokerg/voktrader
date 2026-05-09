package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.trade.TradeOrderStatus;

public final class ExecutorOrderStatusMapper {
    private ExecutorOrderStatusMapper() {
    }

    public static TradeOrderStatus toLifecycleStatus(String status) {
        if (status == null || status.isBlank()) {
            return TradeOrderStatus.UNKNOWN;
        }
        String normalized = status.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CREATED", "PENDING", "PENDING_NEW" -> TradeOrderStatus.CREATED;
            case "SUBMITTING" -> TradeOrderStatus.SUBMITTING;
            case "SUBMITTED", "LIVE", "ACCEPTED", "OPEN", "DRY_RUN_SUBMITTED" -> TradeOrderStatus.SUBMITTED;
            case "RESTING", "BOOKED" -> TradeOrderStatus.RESTING;
            case "PARTIAL", "PARTIALLY_FILLED", "PARTIALLY_MATCHED" -> TradeOrderStatus.PARTIALLY_FILLED;
            case "FILLED", "MATCHED", "DRY_RUN_FILLED" -> TradeOrderStatus.FILLED;
            case "CANCEL_REQUESTED", "PENDING_CANCEL" -> TradeOrderStatus.CANCEL_REQUESTED;
            case "CANCELLED", "CANCELED", "DRY_RUN_CANCELLED" -> TradeOrderStatus.CANCELLED;
            case "EXPIRED", "TIMEOUT", "TIMED_OUT" -> TradeOrderStatus.EXPIRED;
            case "REJECTED", "DENIED" -> TradeOrderStatus.REJECTED;
            case "FAILED", "ERROR" -> TradeOrderStatus.FAILED;
            default -> TradeOrderStatus.UNKNOWN;
        };
    }
}
