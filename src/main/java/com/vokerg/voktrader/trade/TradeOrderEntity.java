package com.vokerg.voktrader.trade;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_trade_orders_idempotency_key", columnNames = "idempotency_key")
}, indexes = {
        @Index(name = "idx_trade_orders_trade", columnList = "trade_id"),
        @Index(name = "idx_trade_orders_exchange", columnList = "exchange_order_id"),
        @Index(name = "idx_trade_orders_status", columnList = "status")
})
public class TradeOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 32)
    private TradeOrderPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private ExecutionMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "venue", nullable = false, length = 32)
    private TradeVenue venue;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private TradeSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 32)
    private TradeOrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TradeOrderStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "client_order_id")
    private String clientOrderId;

    @Column(name = "exchange_order_id")
    private String exchangeOrderId;

    @Column(name = "token_id", nullable = false, length = 120)
    private String tokenId;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "observed_bid", precision = 19, scale = 8)
    private BigDecimal observedBid;

    @Column(name = "observed_ask", precision = 19, scale = 8)
    private BigDecimal observedAsk;

    @Column(name = "observed_spread", precision = 19, scale = 8)
    private BigDecimal observedSpread;

    @Column(name = "observed_midpoint", precision = 19, scale = 8)
    private BigDecimal observedMidpoint;

    @Column(name = "price_updated_at")
    private Instant priceUpdatedAt;

    @Column(name = "price_age_ms")
    private Long priceAgeMs;

    @Column(name = "expected_price", precision = 19, scale = 8)
    private BigDecimal expectedPrice;

    @Column(name = "limit_price", precision = 19, scale = 8)
    private BigDecimal limitPrice;

    @Column(name = "requested_amount_usd", precision = 19, scale = 8)
    private BigDecimal requestedAmountUsd;

    @Column(name = "requested_shares", precision = 19, scale = 8)
    private BigDecimal requestedShares;

    @Column(name = "filled_shares", precision = 19, scale = 8)
    private BigDecimal filledShares;

    @Column(name = "filled_amount_usd", precision = 19, scale = 8)
    private BigDecimal filledAmountUsd;

    @Column(name = "avg_fill_price", precision = 19, scale = 8)
    private BigDecimal avgFillPrice;

    @Column(name = "expected_fee_usd", precision = 19, scale = 8)
    private BigDecimal expectedFeeUsd;

    @Column(name = "actual_fee_usd", precision = 19, scale = 8)
    private BigDecimal actualFeeUsd;

    @Column(name = "expected_slippage", precision = 19, scale = 8)
    private BigDecimal expectedSlippage;

    @Column(name = "actual_slippage", precision = 19, scale = 8)
    private BigDecimal actualSlippage;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "cancel_reason", length = 1000)
    private String cancelReason;

    @Column(name = "reject_reason", length = 1000)
    private String rejectReason;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "raw_request", columnDefinition = "TEXT")
    private String rawRequest;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TradeOrderEntity() {
    }

    public static TradeOrderEntity fromIntent(Long tradeId, TradeIntent intent, ExecutionMode mode, TradeVenue venue, String idempotencyKey) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.tradeId = tradeId;
        entity.phase = intent.side() == TradeSide.BUY ? TradeOrderPhase.ENTRY : TradeOrderPhase.EXIT;
        entity.mode = mode;
        entity.venue = venue;
        entity.side = intent.side();
        entity.orderType = mode == ExecutionMode.PAPER ? TradeOrderType.SIMULATED : intent.orderType();
        entity.status = TradeOrderStatus.CREATED;
        entity.idempotencyKey = idempotencyKey;
        entity.tokenId = intent.tokenId();
        entity.outcome = intent.outcome();
        entity.observedBid = intent.observedBid();
        entity.observedAsk = intent.observedAsk();
        entity.observedSpread = intent.observedSpread();
        entity.observedMidpoint = intent.observedMidpoint();
        entity.priceUpdatedAt = intent.priceUpdatedAt();
        entity.priceAgeMs = intent.priceAgeMs();
        entity.expectedPrice = intent.expectedPrice();
        entity.limitPrice = intent.expectedPrice();
        entity.requestedAmountUsd = intent.amountUsd();
        entity.requestedShares = intent.shares();
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public void markRiskRejected(String reason) {
        this.status = TradeOrderStatus.RISK_REJECTED;
        this.rejectReason = reason;
        touch();
    }

    public void markShadowRecorded(BigDecimal expectedFeeUsd) {
        this.status = TradeOrderStatus.SHADOW_RECORDED;
        this.expectedFeeUsd = expectedFeeUsd;
        this.completedAt = Instant.now();
        touch();
    }

    public void markFilled(BigDecimal shares, BigDecimal amountUsd, BigDecimal avgPrice, BigDecimal feeUsd) {
        this.status = TradeOrderStatus.FILLED;
        this.filledShares = shares;
        this.filledAmountUsd = amountUsd;
        this.avgFillPrice = avgPrice;
        this.expectedFeeUsd = feeUsd;
        this.actualFeeUsd = feeUsd;
        this.completedAt = Instant.now();
        touch();
    }

    public void markFailed(String errorMessage) {
        this.status = TradeOrderStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTradeId() { return tradeId; }
    public TradeOrderPhase getPhase() { return phase; }
    public ExecutionMode getMode() { return mode; }
    public TradeVenue getVenue() { return venue; }
    public TradeSide getSide() { return side; }
    public TradeOrderType getOrderType() { return orderType; }
    public TradeOrderStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getClientOrderId() { return clientOrderId; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public String getTokenId() { return tokenId; }
    public String getOutcome() { return outcome; }
    public BigDecimal getObservedBid() { return observedBid; }
    public BigDecimal getObservedAsk() { return observedAsk; }
    public BigDecimal getObservedSpread() { return observedSpread; }
    public BigDecimal getObservedMidpoint() { return observedMidpoint; }
    public Instant getPriceUpdatedAt() { return priceUpdatedAt; }
    public Long getPriceAgeMs() { return priceAgeMs; }
    public BigDecimal getExpectedPrice() { return expectedPrice; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public BigDecimal getRequestedAmountUsd() { return requestedAmountUsd; }
    public BigDecimal getRequestedShares() { return requestedShares; }
    public BigDecimal getFilledShares() { return filledShares; }
    public BigDecimal getFilledAmountUsd() { return filledAmountUsd; }
    public BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public BigDecimal getExpectedFeeUsd() { return expectedFeeUsd; }
    public BigDecimal getActualFeeUsd() { return actualFeeUsd; }
    public BigDecimal getExpectedSlippage() { return expectedSlippage; }
    public BigDecimal getActualSlippage() { return actualSlippage; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Long getLatencyMs() { return latencyMs; }
    public String getCancelReason() { return cancelReason; }
    public String getRejectReason() { return rejectReason; }
    public String getErrorMessage() { return errorMessage; }
    public String getRawRequest() { return rawRequest; }
    public String getRawResponse() { return rawResponse; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
