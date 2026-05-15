package com.vokerg.voktrader.trade.model;

public enum TradeStatus {
    NEW,
    CREATED,
    RISK_REJECTED,
    ENTRY_PENDING,
    ENTRY_REJECTED,
    PARTIALLY_OPEN,
    OPEN,
    EXIT_PENDING,
    PARTIALLY_CLOSED,
    CLOSED,
    RESOLVED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    public boolean isActive() {
        return switch (this) {
            case ENTRY_PENDING, PARTIALLY_OPEN, OPEN, EXIT_PENDING, PARTIALLY_CLOSED -> true;
            default -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case CLOSED, RESOLVED, CANCELLED, FAILED, RISK_REJECTED, ENTRY_REJECTED -> true;
            default -> false;
        };
    }

    public boolean isPendingEntry() {
        return this == ENTRY_PENDING;
    }

    public boolean isPendingExit() {
        return this == EXIT_PENDING;
    }

    public boolean hasPosition() {
        return switch (this) {
            case PARTIALLY_OPEN, OPEN, EXIT_PENDING, PARTIALLY_CLOSED -> true;
            default -> false;
        };
    }
}
