package com.vokerg.voktrader.polymarket.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "user_websocket_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_ws_events_dedupe", columnNames = "dedupe_key"),
        indexes = {
                @Index(name = "idx_user_ws_events_order", columnList = "remote_order_id,received_at"),
                @Index(name = "idx_user_ws_events_trade", columnList = "remote_trade_id,received_at"),
                @Index(name = "idx_user_ws_events_received", columnList = "received_at")
        }
)
public class UserWebSocketEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, length = 192)
    private String dedupeKey;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "remote_event_id", length = 255)
    private String remoteEventId;

    @Column(name = "remote_order_id", length = 255)
    private String remoteOrderId;

    @Column(name = "remote_trade_id", length = 255)
    private String remoteTradeId;

    @Column(name = "market_id", length = 255)
    private String marketId;

    @Column(name = "token_id", length = 255)
    private String tokenId;

    @Column(name = "side", length = 16)
    private String side;

    @Column(name = "lifecycle_status", length = 64)
    private String lifecycleStatus;

    @Column(name = "event_timestamp")
    private Instant eventTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "connection_generation", nullable = false)
    private long connectionGeneration;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "processing_note", columnDefinition = "TEXT")
    private String processingNote;

    @Column(name = "trade_order_id")
    private Long tradeOrderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserWebSocketEventEntity() {
    }

    public static UserWebSocketEventEntity from(
            String dedupeKey,
            UserWebSocketMessage message,
            String rawPayload,
            long generation,
            Instant receivedAt
    ) {
        UserWebSocketEventEntity entity = new UserWebSocketEventEntity();
        entity.dedupeKey = dedupeKey;
        entity.eventType = message.eventType();
        entity.remoteEventId = message.id();
        entity.remoteOrderId = message.remoteOrderIds().stream().findFirst().orElse(null);
        entity.remoteTradeId = message.remoteTradeId();
        entity.marketId = message.market();
        entity.tokenId = message.assetId();
        entity.side = message.side();
        entity.lifecycleStatus = message.lifecycleStatus();
        entity.eventTimestamp = message.eventTimestamp();
        entity.receivedAt = receivedAt;
        entity.connectionGeneration = generation;
        entity.rawPayload = rawPayload;
        return entity;
    }

    public void markProcessed(Long tradeOrderId, String processingNote) {
        this.processed = true;
        this.tradeOrderId = tradeOrderId;
        this.processingNote = processingNote;
    }

    public void markUnresolved(String processingNote) {
        this.processed = false;
        this.processingNote = processingNote;
    }

    @PrePersist
    void prePersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (createdAt == null) {
            createdAt = receivedAt;
        }
    }

    public Long getId() {
        return id;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRemoteEventId() {
        return remoteEventId;
    }

    public String getRemoteOrderId() {
        return remoteOrderId;
    }

    public String getRemoteTradeId() {
        return remoteTradeId;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public long getConnectionGeneration() {
        return connectionGeneration;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public boolean isProcessed() {
        return processed;
    }

    public String getProcessingNote() {
        return processingNote;
    }

    public Long getTradeOrderId() {
        return tradeOrderId;
    }
}
