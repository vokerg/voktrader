package com.vokerg.voktrader.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "outbox_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_outbox_events_idempotency_key", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_outbox_events_pending", columnList = "type,status,next_attempt_at,created_at"),
                @Index(name = "idx_outbox_events_aggregate", columnList = "aggregate_type,aggregate_id")
        }
)
public class OutboxEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private OutboxEventType type;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxEventStatus status;

    @Column(name = "aggregate_type", nullable = false, length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    protected OutboxEventEntity() {
    }

    public static OutboxEventEntity pending(
            OutboxEventType type,
            String idempotencyKey,
            String aggregateType,
            String aggregateId,
            String payloadJson,
            Instant now
    ) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.type = type;
        entity.idempotencyKey = idempotencyKey;
        entity.aggregateType = aggregateType;
        entity.aggregateId = aggregateId;
        entity.payloadJson = payloadJson;
        entity.status = OutboxEventStatus.PENDING;
        entity.createdAt = now;
        entity.nextAttemptAt = now;
        entity.attempts = 0;
        return entity;
    }

    public void markProcessing() {
        this.status = OutboxEventStatus.PROCESSING;
    }

    public void markProcessed(Instant processedAt) {
        this.status = OutboxEventStatus.PROCESSED;
        this.processedAt = processedAt == null ? Instant.now() : processedAt;
        this.lastError = null;
    }

    public void markRetry(String error, Instant nextAttemptAt) {
        this.status = OutboxEventStatus.PENDING;
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt == null ? Instant.now() : nextAttemptAt;
    }

    public void markFailed(String error) {
        this.status = OutboxEventStatus.FAILED;
        this.attempts++;
        this.lastError = truncate(error);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }

    public Long getId() { return id; }
    public OutboxEventType getType() { return type; }
    public String getPayloadJson() { return payloadJson; }
    public OutboxEventStatus getStatus() { return status; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
