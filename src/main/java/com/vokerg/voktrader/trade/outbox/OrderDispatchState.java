package com.vokerg.voktrader.trade.outbox;

public enum OrderDispatchState {
    OUTBOX_READY,
    SUBMITTING,
    SUBMITTED,
    UNKNOWN,
    RECONCILE,
    MANUAL_REVIEW,
    COMPLETED,
    FAILED,
    CANCELLED
}
