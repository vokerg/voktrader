package com.vokerg.voktrader.trade.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "trade_events", indexes = {
        @Index(name = "idx_trade_events_trade", columnList = "trade_id"),
        @Index(name = "idx_trade_events_type", columnList = "event_type")
})
public class TradeEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id")
    private Long tradeId;

    @Column(name = "trade_order_id")
    private Long tradeOrderId;

    @Column(name = "trade_fill_id")
    private Long tradeFillId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TradeEventEntity() {
    }

    public static TradeEventEntity of(Long tradeId, Long orderId, Long fillId, String eventType, String message, String payloadJson) {
        TradeEventEntity entity = new TradeEventEntity();
        entity.tradeId = tradeId;
        entity.tradeOrderId = orderId;
        entity.tradeFillId = fillId;
        entity.eventType = eventType;
        entity.message = message;
        entity.payloadJson = payloadJson;
        entity.createdAt = Instant.now();
        return entity;
    }

    public Long getId() { return id; }
    public Long getTradeId() { return tradeId; }
    public Long getTradeOrderId() { return tradeOrderId; }
    public Long getTradeFillId() { return tradeFillId; }
    public String getEventType() { return eventType; }
    public String getMessage() { return message; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}
