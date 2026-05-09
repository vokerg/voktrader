package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.economy.LiquidityRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import com.vokerg.voktrader.time.TimeMachine;

@Entity
@Table(
        name = "trade_orders",
        indexes = {
                @Index(name = "idx_trade_orders_trade", columnList = "tradeId"),
                @Index(name = "idx_trade_orders_market", columnList = "marketId,tokenId"),
                @Index(name = "idx_trade_orders_client", columnList = "clientOrderId", unique = true)
        }
)
public class TradeOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; private Long botId; private Long tradeId;
    private String localOrderId;
    private String clientOrderId;
    private String idempotencyKey;
    private String remoteOrderId;
    private String exchangeOrderId;
    private String strategyId;
    private String configHash;
    private String accountId;
    private String ruleId;
    private String marketId;
    private String tokenId;
    private String outcome;

    @Enumerated(EnumType.STRING)
    private TradeSide side;

    @Enumerated(EnumType.STRING)
    private TradeOrderPhase phase;

    @Enumerated(EnumType.STRING)
    private ExecutionMode mode;

    @Enumerated(EnumType.STRING)
    private TradeVenue venue;

    @Enumerated(EnumType.STRING)
    private TradeOrderType orderType;

    private Boolean postOnly;

    @Enumerated(EnumType.STRING)
    private TradeOrderStatus status;

    private BigDecimal requestedPrice;
    private BigDecimal requestedShares;
    private BigDecimal requestedAmountUsd;
    private BigDecimal filledPrice;
    private BigDecimal filledShares;
    private BigDecimal filledAmountUsd;
    private BigDecimal remainingShares;
    private BigDecimal avgFillPrice;
    private BigDecimal realizedFeeUsd;

    @Enumerated(EnumType.STRING)
    private LiquidityRole fillRole;

    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(columnDefinition = "TEXT")
    private String cancelReason;

    @Column(columnDefinition = "TEXT")
    private String rawRequest;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    private Instant submittedAt;
    private Instant acknowledgedAt;
    private Instant completedAt;
    private Instant lastReconciledAt;
    private Instant expiresAt;
    private Long latencyMs;
    private Instant createdAt;
    private Instant updatedAt;

    public static TradeOrderEntity fromIntent(Long tradeId, TradeIntent intent, String clientOrderId) {
        return fromIntent(tradeId, intent, ExecutionMode.PAPER, TradeVenue.PAPER_SIM, clientOrderId);
    }

    public static TradeOrderEntity fromIntent(
            Long tradeId,
            TradeIntent intent,
            ExecutionMode mode,
            TradeVenue venue,
            String clientOrderId
    ) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.botId = intent.botId(); entity.tradeId = tradeId;
        entity.localOrderId = clientOrderId;
        entity.clientOrderId = clientOrderId;
        entity.idempotencyKey = clientOrderId;
        entity.strategyId = intent.strategyId();
        entity.ruleId = intent.ruleId();
        entity.marketId = intent.marketId();
        entity.tokenId = intent.tokenId();
        entity.outcome = intent.outcome();
        entity.side = intent.side();
        entity.phase = intent.side() == TradeSide.BUY ? TradeOrderPhase.ENTRY : TradeOrderPhase.EXIT;
        entity.mode = mode;
        entity.venue = venue;
        entity.orderType = intent.orderType();
        entity.postOnly = intent.postOnly();
        entity.status = TradeOrderStatus.CREATED;
        entity.requestedPrice = intent.expectedPrice();
        entity.requestedShares = intent.shares();
        entity.requestedAmountUsd = intent.amountUsd();
        entity.remainingShares = intent.shares();
        return entity;
    }

    public void markRiskRejected(String reason) {
        this.status = TradeOrderStatus.RISK_REJECTED;
        this.rejectReason = reason;
        this.rejectionReason = reason;
        this.completedAt = TimeMachine.now();
        touch();
    }

    public void markShadowRecorded() {
        this.status = TradeOrderStatus.SHADOW_RECORDED;
        this.submittedAt = TimeMachine.now();
        this.acknowledgedAt = this.submittedAt;
        this.completedAt = this.submittedAt;
        this.latencyMs = 0L;
        touch();
    }

    public void markSubmitting(String clientOrderId, String rawRequest) {
        this.status = TradeOrderStatus.SUBMITTING;
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            this.localOrderId = clientOrderId;
            this.clientOrderId = clientOrderId;
            this.idempotencyKey = clientOrderId;
        }
        this.rawRequest = rawRequest;
        this.submittedAt = TimeMachine.now();
        touch();
    }

    public void markSubmitted(String exchangeOrderId, String rawResponse) {
        attachExecutorResponse(exchangeOrderId, rawResponse);
        this.status = TradeOrderStatus.SUBMITTED;
        touch();
    }

    public void attachExecutorResponse(String exchangeOrderId, String rawResponse) {
        if (exchangeOrderId != null && !exchangeOrderId.isBlank()) {
            this.exchangeOrderId = exchangeOrderId;
            this.remoteOrderId = exchangeOrderId;
        }
        this.rawResponse = rawResponse;
        this.acknowledgedAt = TimeMachine.now();
        if (this.submittedAt != null) {
            this.latencyMs = Duration.between(this.submittedAt, this.acknowledgedAt).toMillis();
        }
        touch();
    }

    public void markFilled(String exchangeOrderId, BigDecimal price, BigDecimal shares, BigDecimal amountUsd) {
        this.status = TradeOrderStatus.FILLED;
        this.exchangeOrderId = exchangeOrderId;
        this.remoteOrderId = exchangeOrderId;
        this.filledPrice = price;
        this.filledShares = shares;
        this.filledAmountUsd = amountUsd;
        this.avgFillPrice = price;
        this.remainingShares = remainingSharesAfterFill(shares);
        this.acknowledgedAt = TimeMachine.now();
        this.completedAt = this.acknowledgedAt;
        if (this.submittedAt != null) {
            this.latencyMs = Duration.between(this.submittedAt, this.completedAt).toMillis();
        }
        touch();
    }

    public void markFailed(String errorMessage) {
        this.status = TradeOrderStatus.FAILED;
        this.errorMessage = errorMessage;
        this.failureReason = errorMessage;
        this.completedAt = TimeMachine.now();
        touch();
    }

    public void markFailed(String errorMessage, String rawResponse) {
        this.rawResponse = rawResponse;
        markFailed(errorMessage);
    }

    private void touch() {
        this.updatedAt = TimeMachine.now();
    }

    @PrePersist
    void prePersist() {
        Instant now = TimeMachine.now();
        if (this.idempotencyKey == null || this.idempotencyKey.isBlank()) {
            this.idempotencyKey = this.clientOrderId;
        }
        if (this.localOrderId == null || this.localOrderId.isBlank()) {
            this.localOrderId = this.clientOrderId;
        }
        if (this.remoteOrderId == null || this.remoteOrderId.isBlank()) {
            this.remoteOrderId = this.exchangeOrderId;
        }
        if (this.phase == null && this.side != null) {
            this.phase = this.side == TradeSide.BUY ? TradeOrderPhase.ENTRY : TradeOrderPhase.EXIT;
        }
        if (this.mode == null) {
            this.mode = ExecutionMode.PAPER;
        }
        if (this.venue == null) {
            this.venue = TradeVenue.PAPER_SIM;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = TimeMachine.now();
    }

    private BigDecimal remainingSharesAfterFill(BigDecimal shares) {
        if (requestedShares == null || shares == null) {
            return null;
        }
        BigDecimal remaining = requestedShares.subtract(shares);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    public Long getId() {
        return id;
    }

    public Long getBotId() { return botId; } public Long getTradeId() {
        return tradeId;
    }

    public String getLocalOrderId() {
        return localOrderId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRemoteOrderId() {
        return remoteOrderId;
    }

    public String getExchangeOrderId() {
        return exchangeOrderId;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public String getConfigHash() {
        return configHash;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getMarketId() {
        return marketId;
    }

    public String getTokenId() {
        return tokenId;
    }

    public String getOutcome() {
        return outcome;
    }

    public TradeSide getSide() {
        return side;
    }

    public TradeOrderPhase getPhase() {
        return phase;
    }

    public ExecutionMode getMode() {
        return mode;
    }

    public TradeVenue getVenue() {
        return venue;
    }

    public TradeOrderType getOrderType() {
        return orderType;
    }

    public Boolean getPostOnly() {
        return postOnly;
    }

    public TradeOrderStatus getStatus() {
        return status;
    }

    public BigDecimal getRequestedPrice() {
        return requestedPrice;
    }

    public BigDecimal getRequestedShares() {
        return requestedShares;
    }

    public BigDecimal getRequestedAmountUsd() {
        return requestedAmountUsd;
    }

    public BigDecimal getFilledPrice() {
        return filledPrice;
    }

    public BigDecimal getFilledShares() {
        return filledShares;
    }

    public BigDecimal getFilledAmountUsd() {
        return filledAmountUsd;
    }

    public BigDecimal getRemainingShares() {
        return remainingShares;
    }

    public BigDecimal getAvgFillPrice() {
        return avgFillPrice;
    }

    public BigDecimal getRealizedFeeUsd() {
        return realizedFeeUsd;
    }

    public LiquidityRole getFillRole() {
        return fillRole;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getRawRequest() {
        return rawRequest;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getLastReconciledAt() {
        return lastReconciledAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
