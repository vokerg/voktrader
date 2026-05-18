package com.vokerg.voktrader.outbox;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
