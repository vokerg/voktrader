package com.vokerg.voktrader.trade.model;

public enum TradeOrderStatus {
    CREATED,
    SUBMITTED,
    RESTING,
    PARTIALLY_FILLED,
    PARTIALLY_FILLED_DONE,
    FILLED,
    CANCEL_REQUESTED,
    CANCELLED,
    EXPIRED,
    REJECTED,
    FAILED,
    UNKNOWN,
    RISK_REJECTED,
    SHADOW_RECORDED,
    SUBMITTING,
    OPEN,
    PARTIAL,
    TIMEOUT;

    public boolean isActive() {
        return switch (this) {
            case CREATED, SUBMITTING, SUBMITTED, RESTING, OPEN, PARTIALLY_FILLED, PARTIAL, CANCEL_REQUESTED -> true;
            default -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case FILLED, PARTIALLY_FILLED_DONE, CANCELLED, EXPIRED, TIMEOUT, REJECTED, RISK_REJECTED, FAILED, SHADOW_RECORDED -> true;
            default -> false;
        };
    }

    public boolean isFilledOrPartiallyFilled() {
        return this == FILLED || this == PARTIALLY_FILLED || this == PARTIALLY_FILLED_DONE || this == PARTIAL;
    }
}
