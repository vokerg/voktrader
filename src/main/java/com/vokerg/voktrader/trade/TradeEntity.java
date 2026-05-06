package com.vokerg.voktrader.trade;

import jakarta.persistence.*;
import com.vokerg.voktrader.time.TimeMachine;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trades", indexes = {
        @Index(name = "idx_trades_market_status", columnList = "market_id,status"), @Index(name = "idx_trades_bot_market_status", columnList = "bot_id,market_id,status"),
        @Index(name = "idx_trades_strategy", columnList = "strategy_id"),
        @Index(name = "idx_trades_token", columnList = "token_id")
})
public class TradeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; @Column(name = "bot_id") private Long botId; @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private ExecutionMode mode;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "market_id", nullable = false)
    private String marketId;

    @Column(name = "market_slug")
    private String marketSlug;

    @Column(name = "question", length = 1000)
    private String question;

    @Column(name = "condition_id")
    private String conditionId;

    @Column(name = "token_id", nullable = false, length = 120)
    private String tokenId;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TradeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_side", nullable = false, length = 16)
    private TradeSide decisionSide;

    @Column(name = "decision_reason", length = 2000)
    private String decisionReason;

    @Column(name = "decision_at", nullable = false)
    private Instant decisionAt;

    @Column(name = "market_end_at")
    private Instant marketEndAt;

    @Column(name = "seconds_to_expiry_at_decision")
    private Long secondsToExpiryAtDecision;

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

    @Column(name = "intended_amount_usd", precision = 19, scale = 8)
    private BigDecimal intendedAmountUsd;

    @Column(name = "intended_shares", precision = 19, scale = 8)
    private BigDecimal intendedShares;

    @Column(name = "intended_entry_price", precision = 19, scale = 8)
    private BigDecimal intendedEntryPrice;

    @Column(name = "intended_exit_price", precision = 19, scale = 8)
    private BigDecimal intendedExitPrice;

    @Column(name = "max_slippage_price", precision = 19, scale = 8)
    private BigDecimal maxSlippagePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_order_type", length = 32)
    private TradeOrderType entryOrderType;

    @Column(name = "entry_avg_price", precision = 19, scale = 8)
    private BigDecimal entryAvgPrice;

    @Column(name = "entry_filled_shares", precision = 19, scale = 8)
    private BigDecimal entryFilledShares;

    @Column(name = "entry_filled_usd", precision = 19, scale = 8)
    private BigDecimal entryFilledUsd;

    @Column(name = "entry_fee_usd", precision = 19, scale = 8)
    private BigDecimal entryFeeUsd;

    @Column(name = "entry_completed_at")
    private Instant entryCompletedAt;

    @Column(name = "exit_avg_price", precision = 19, scale = 8)
    private BigDecimal exitAvgPrice;

    @Column(name = "exit_filled_shares", precision = 19, scale = 8)
    private BigDecimal exitFilledShares;

    @Column(name = "exit_filled_usd", precision = 19, scale = 8)
    private BigDecimal exitFilledUsd;

    @Column(name = "exit_fee_usd", precision = 19, scale = 8)
    private BigDecimal exitFeeUsd;

    @Column(name = "exit_completed_at")
    private Instant exitCompletedAt;

    @Column(name = "total_fee_usd", precision = 19, scale = 8)
    private BigDecimal totalFeeUsd;

    @Column(name = "realized_pnl_usd", precision = 19, scale = 8)
    private BigDecimal realizedPnlUsd;

    @Column(name = "resolved_pnl_usd", precision = 19, scale = 8)
    private BigDecimal resolvedPnlUsd;

    @Column(name = "final_pnl_usd", precision = 19, scale = 8)
    private BigDecimal finalPnlUsd;

    @Column(name = "winning_outcome")
    private String winningOutcome;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "backtest_run_id", length = 64)
    private String backtestRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TradeEntity() {
    }

    public static TradeEntity fromIntent(TradeIntent intent, ExecutionMode mode) {
        TradeEntity entity = new TradeEntity();
        entity.botId = intent.botId(); entity.mode = mode;
        entity.strategyId = intent.strategyId();
        entity.ruleId = intent.ruleId();
        entity.marketId = intent.marketId();
        entity.marketSlug = intent.marketSlug();
        entity.question = intent.question();
        entity.conditionId = intent.conditionId();
        entity.tokenId = intent.tokenId();
        entity.outcome = intent.outcome();
        entity.status = TradeStatus.CREATED;
        entity.decisionSide = intent.side();
        entity.decisionReason = intent.reason();
        entity.decisionAt = intent.decisionAt();
        entity.marketEndAt = intent.marketEndAt();
        entity.secondsToExpiryAtDecision = intent.secondsToExpiryAtDecision();
        entity.observedBid = intent.observedBid();
        entity.observedAsk = intent.observedAsk();
        entity.observedSpread = intent.observedSpread();
        entity.observedMidpoint = intent.observedMidpoint();
        entity.priceUpdatedAt = intent.priceUpdatedAt();
        entity.priceAgeMs = intent.priceAgeMs();
        entity.intendedAmountUsd = intent.amountUsd();
        entity.intendedShares = intent.shares();
        entity.intendedEntryPrice = intent.side() == TradeSide.BUY ? intent.expectedPrice() : null;
        entity.intendedExitPrice = intent.side() == TradeSide.SELL ? intent.expectedPrice() : null;
        entity.entryOrderType = intent.side() == TradeSide.BUY ? intent.orderType() : null;
        entity.createdAt = TimeMachine.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public void markRiskRejected() {
        this.status = TradeStatus.RISK_REJECTED;
        touch();
    }

    public void markEntryPending() {
        this.status = TradeStatus.ENTRY_PENDING;
        touch();
    }

    public void markOpen(BigDecimal avgPrice, BigDecimal filledShares, BigDecimal filledUsd, BigDecimal feeUsd, Instant completedAt) {
        this.status = TradeStatus.OPEN;
        this.entryAvgPrice = avgPrice;
        this.entryFilledShares = filledShares;
        this.entryFilledUsd = filledUsd;
        this.entryFeeUsd = feeUsd;
        this.totalFeeUsd = feeUsd;
        this.entryCompletedAt = completedAt == null ? Instant.now() : completedAt;
        touch();
    }

    public void markClosed(BigDecimal avgPrice, BigDecimal filledShares, BigDecimal filledUsd, BigDecimal feeUsd, Instant completedAt) {
        this.status = TradeStatus.CLOSED;
        this.exitAvgPrice = avgPrice;
        this.exitFilledShares = filledShares;
        this.exitFilledUsd = filledUsd;
        this.exitFeeUsd = feeUsd;
        this.exitCompletedAt = completedAt == null ? Instant.now() : completedAt;
        BigDecimal entryFee = entryFeeUsd == null ? BigDecimal.ZERO : entryFeeUsd;
        BigDecimal exitFee = feeUsd == null ? BigDecimal.ZERO : feeUsd;
        BigDecimal totalFees = entryFee.add(exitFee);
        this.totalFeeUsd = totalFees;
        BigDecimal entryCost = entryFilledUsd == null ? BigDecimal.ZERO : entryFilledUsd;
        BigDecimal exitValue = filledUsd == null ? BigDecimal.ZERO : filledUsd;
        this.realizedPnlUsd = exitValue.subtract(entryCost).subtract(totalFees);
        this.finalPnlUsd = this.realizedPnlUsd;
        touch();
    }

    public void markShadowRecorded() {
        this.status = TradeStatus.CREATED;
        touch();
    }

    public void markFailed(String reason) {
        this.status = TradeStatus.FAILED;
        touch();
    }

    public void resolve(String winningOutcome, Instant resolvedAt) {
        this.winningOutcome = winningOutcome;
        this.resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
        boolean won = this.outcome != null && winningOutcome != null && this.outcome.equalsIgnoreCase(winningOutcome);
        BigDecimal cost = entryFilledUsd == null ? BigDecimal.ZERO : entryFilledUsd;
        BigDecimal fee = totalFeeUsd == null ? BigDecimal.ZERO : totalFeeUsd;
        BigDecimal resolutionValue = (won && entryFilledShares != null) ? entryFilledShares : BigDecimal.ZERO;
        this.resolvedPnlUsd = resolutionValue.subtract(cost).subtract(fee);
        this.finalPnlUsd = this.resolvedPnlUsd;
        this.status = TradeStatus.RESOLVED;
        touch();
    }

    public void attachBacktestRun(String backtestRunId) {
        this.backtestRunId = backtestRunId;
        touch();
    }

    private void touch() {
        this.updatedAt = TimeMachine.now();
    }

    public Long getId() { return id; }
    public Long getBotId() { return botId; } public ExecutionMode getMode() { return mode; }
    public String getStrategyId() { return strategyId; }
    public String getRuleId() { return ruleId; }
    public String getMarketId() { return marketId; }
    public String getMarketSlug() { return marketSlug; }
    public String getQuestion() { return question; }
    public String getConditionId() { return conditionId; }
    public String getTokenId() { return tokenId; }
    public String getOutcome() { return outcome; }
    public TradeStatus getStatus() { return status; }
    public TradeSide getDecisionSide() { return decisionSide; }
    public String getDecisionReason() { return decisionReason; }
    public Instant getDecisionAt() { return decisionAt; }
    public Instant getMarketEndAt() { return marketEndAt; }
    public Long getSecondsToExpiryAtDecision() { return secondsToExpiryAtDecision; }
    public BigDecimal getObservedBid() { return observedBid; }
    public BigDecimal getObservedAsk() { return observedAsk; }
    public BigDecimal getObservedSpread() { return observedSpread; }
    public BigDecimal getObservedMidpoint() { return observedMidpoint; }
    public Instant getPriceUpdatedAt() { return priceUpdatedAt; }
    public Long getPriceAgeMs() { return priceAgeMs; }
    public BigDecimal getIntendedAmountUsd() { return intendedAmountUsd; }
    public BigDecimal getIntendedShares() { return intendedShares; }
    public BigDecimal getIntendedEntryPrice() { return intendedEntryPrice; }
    public BigDecimal getIntendedExitPrice() { return intendedExitPrice; }
    public BigDecimal getMaxSlippagePrice() { return maxSlippagePrice; }
    public TradeOrderType getEntryOrderType() { return entryOrderType; }
    public BigDecimal getEntryAvgPrice() { return entryAvgPrice; }
    public BigDecimal getEntryFilledShares() { return entryFilledShares; }
    public BigDecimal getEntryFilledUsd() { return entryFilledUsd; }
    public BigDecimal getEntryFeeUsd() { return entryFeeUsd; }
    public Instant getEntryCompletedAt() { return entryCompletedAt; }
    public BigDecimal getExitAvgPrice() { return exitAvgPrice; }
    public BigDecimal getExitFilledShares() { return exitFilledShares; }
    public BigDecimal getExitFilledUsd() { return exitFilledUsd; }
    public BigDecimal getExitFeeUsd() { return exitFeeUsd; }
    public Instant getExitCompletedAt() { return exitCompletedAt; }
    public BigDecimal getTotalFeeUsd() { return totalFeeUsd; }
    public BigDecimal getRealizedPnlUsd() { return realizedPnlUsd; }
    public BigDecimal getResolvedPnlUsd() { return resolvedPnlUsd; }
    public BigDecimal getFinalPnlUsd() { return finalPnlUsd; }
    public String getWinningOutcome() { return winningOutcome; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getBacktestRunId() { return backtestRunId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
