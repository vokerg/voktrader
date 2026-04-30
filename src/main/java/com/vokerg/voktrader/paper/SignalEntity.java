package com.vokerg.voktrader.paper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "signals",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_signal_market_token_rule_type",
                        columnNames = {"market_id", "token_id", "rule_name", "signal_type"}
                )
        }
)
public class SignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false)
    private String marketId;

    @Column(name = "market_slug")
    private String marketSlug;

    @Column(name = "question", length = 1000)
    private String question;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "token_id", nullable = false, length = 100)
    private String tokenId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, columnDefinition = "VARCHAR(20)")
    private SignalType signalType;

    @Column(name = "entry_price", nullable = false, precision = 19, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "size_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal sizeUsd;

    @Column(name = "shares", nullable = false, precision = 19, scale = 8)
    private BigDecimal shares;

    @Column(name = "decision_bid", precision = 19, scale = 8)
    private BigDecimal decisionBid;

    @Column(name = "decision_ask", precision = 19, scale = 8)
    private BigDecimal decisionAsk;

    @Column(name = "decision_spread", precision = 19, scale = 8)
    private BigDecimal decisionSpread;

    @Column(name = "decision_price_updated_at")
    private Instant decisionPriceUpdatedAt;

    @Column(name = "snapshot_age_ms")
    private Long snapshotAgeMs;

    @Column(name = "fee_rate", precision = 19, scale = 8)
    private BigDecimal feeRate;

    @Column(name = "entry_fee_usd", precision = 19, scale = 8)
    private BigDecimal entryFeeUsd;

    @Column(name = "exit_fee_usd", precision = 19, scale = 8)
    private BigDecimal exitFeeUsd;

    @Column(name = "total_fee_usd", precision = 19, scale = 8)
    private BigDecimal totalFeeUsd;

    @Column(name = "gross_shares", precision = 19, scale = 8)
    private BigDecimal grossShares;

    @Column(name = "net_shares", precision = 19, scale = 8)
    private BigDecimal netShares;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "market_end_date")
    private Instant marketEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(20)")
    private SignalStatus status;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "winning_outcome")
    private String winningOutcome;

    @Column(name = "pnl_usd", precision = 19, scale = 8)
    private BigDecimal pnlUsd;

    @Column(name = "exit_price", precision = 19, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "exit_value_usd", precision = 19, scale = 8)
    private BigDecimal exitValueUsd;

    @Column(name = "exit_reason", length = 1000)
    private String exitReason;

    protected SignalEntity() {
    }

    public static SignalEntity openPaperBuySignal(
            String marketId,
            String marketSlug,
            String question,
            String outcome,
            String tokenId,
            BigDecimal entryPrice,
            BigDecimal sizeUsd,
            BigDecimal grossShares,
            BigDecimal feeRate,
            BigDecimal entryFeeUsd,
            BigDecimal netShares,
            BigDecimal decisionBid,
            BigDecimal decisionAsk,
            BigDecimal decisionSpread,
            Instant decisionPriceUpdatedAt,
            Long snapshotAgeMs,
            String ruleName,
            String reason,
            Instant createdAt,
            Instant marketEndDate
    ) {
        SignalEntity entity = new SignalEntity();
        entity.marketId = marketId;
        entity.marketSlug = marketSlug;
        entity.question = question;
        entity.outcome = outcome;
        entity.tokenId = tokenId;
        entity.signalType = SignalType.PAPER;
        entity.entryPrice = entryPrice;
        entity.sizeUsd = sizeUsd;
        entity.shares = netShares;
        entity.decisionBid = decisionBid;
        entity.decisionAsk = decisionAsk;
        entity.decisionSpread = decisionSpread;
        entity.decisionPriceUpdatedAt = decisionPriceUpdatedAt;
        entity.snapshotAgeMs = snapshotAgeMs;
        entity.feeRate = feeRate;
        entity.entryFeeUsd = entryFeeUsd;
        entity.grossShares = grossShares;
        entity.netShares = netShares;
        entity.ruleName = ruleName;
        entity.reason = reason;
        entity.createdAt = createdAt;
        entity.marketEndDate = marketEndDate;
        entity.status = SignalStatus.OPEN;
        return entity;
    }

    public void sell(BigDecimal exitPrice, BigDecimal exitFeeUsd, String exitReason, Instant soldAt) {
        if (status != SignalStatus.OPEN) {
            throw new IllegalStateException("Only OPEN signals can be sold");
        }

        this.exitPrice = exitPrice;
        this.exitValueUsd = sharesForPnl().multiply(exitPrice);
        this.exitFeeUsd = exitFeeUsd == null ? BigDecimal.ZERO : exitFeeUsd;
        this.totalFeeUsd = entryFeeOrZero().add(this.exitFeeUsd);
        this.pnlUsd = exitValueUsd.subtract(this.exitFeeUsd).subtract(sizeUsd);
        this.exitReason = exitReason;
        this.resolvedAt = soldAt;
        this.status = SignalStatus.SOLD;
    }

    public void resolve(String winningOutcome, Instant resolvedAt) {
        if (status != SignalStatus.OPEN) {
            throw new IllegalStateException("Only OPEN signals can be resolved");
        }

        this.winningOutcome = winningOutcome;
        this.resolvedAt = resolvedAt;

        boolean won = this.outcome.equalsIgnoreCase(winningOutcome);

        if (won) {
            this.status = SignalStatus.WON;
            this.pnlUsd = sharesForPnl().subtract(sizeUsd);
        } else {
            this.status = SignalStatus.LOST;
            this.pnlUsd = sizeUsd.negate();
        }
    }

    private BigDecimal sharesForPnl() {
        if (netShares != null) {
            return netShares;
        }

        return shares;
    }

    private BigDecimal entryFeeOrZero() {
        return entryFeeUsd == null ? BigDecimal.ZERO : entryFeeUsd;
    }

    public Long getId() {
        return id;
    }

    public String getMarketId() {
        return marketId;
    }

    public String getMarketSlug() {
        return marketSlug;
    }

    public String getQuestion() {
        return question;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getTokenId() {
        return tokenId;
    }

    public SignalType getSignalType() {
        return signalType;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public BigDecimal getSizeUsd() {
        return sizeUsd;
    }

    public BigDecimal getShares() {
        return sharesForPnl();
    }

    public BigDecimal getDecisionBid() {
        return decisionBid;
    }

    public BigDecimal getDecisionAsk() {
        return decisionAsk;
    }

    public BigDecimal getDecisionSpread() {
        return decisionSpread;
    }

    public Instant getDecisionPriceUpdatedAt() {
        return decisionPriceUpdatedAt;
    }

    public Long getSnapshotAgeMs() {
        return snapshotAgeMs;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public BigDecimal getEntryFeeUsd() {
        return entryFeeUsd;
    }

    public BigDecimal getExitFeeUsd() {
        return exitFeeUsd;
    }

    public BigDecimal getTotalFeeUsd() {
        return totalFeeUsd;
    }

    public BigDecimal getGrossShares() {
        return grossShares;
    }

    public BigDecimal getNetShares() {
        return sharesForPnl();
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getMarketEndDate() {
        return marketEndDate;
    }

    public SignalStatus getStatus() {
        return status;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getWinningOutcome() {
        return winningOutcome;
    }

    public BigDecimal getPnlUsd() {
        return pnlUsd;
    }

    public BigDecimal getExitPrice() {
        return exitPrice;
    }

    public BigDecimal getExitValueUsd() {
        return exitValueUsd;
    }

    public String getExitReason() {
        return exitReason;
    }
}
