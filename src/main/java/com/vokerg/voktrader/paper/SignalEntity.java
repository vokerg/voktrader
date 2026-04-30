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

    @Column(name = "paper_size_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal paperSizeUsd;

    @Column(name = "paper_shares", nullable = false, precision = 19, scale = 8)
    private BigDecimal paperShares;

    @Column(name = "fee_rate", precision = 19, scale = 8)
    private BigDecimal feeRate;

    @Column(name = "entry_fee_usd", precision = 19, scale = 8)
    private BigDecimal entryFeeUsd;

    @Column(name = "exit_fee_usd", precision = 19, scale = 8)
    private BigDecimal exitFeeUsd;

    @Column(name = "total_fee_usd", precision = 19, scale = 8)
    private BigDecimal totalFeeUsd;

    @Column(name = "gross_paper_shares", precision = 19, scale = 8)
    private BigDecimal grossPaperShares;

    @Column(name = "net_paper_shares", precision = 19, scale = 8)
    private BigDecimal netPaperShares;

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

    @Column(name = "paper_pnl", precision = 19, scale = 8)
    private BigDecimal paperPnl;

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
            BigDecimal paperSizeUsd,
            BigDecimal grossPaperShares,
            BigDecimal feeRate,
            BigDecimal entryFeeUsd,
            BigDecimal netPaperShares,
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
        entity.paperSizeUsd = paperSizeUsd;
        entity.paperShares = netPaperShares;
        entity.feeRate = feeRate;
        entity.entryFeeUsd = entryFeeUsd;
        entity.grossPaperShares = grossPaperShares;
        entity.netPaperShares = netPaperShares;
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
        this.paperPnl = exitValueUsd.subtract(this.exitFeeUsd).subtract(paperSizeUsd);
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
            this.paperPnl = sharesForPnl().subtract(paperSizeUsd);
        } else {
            this.status = SignalStatus.LOST;
            this.paperPnl = paperSizeUsd.negate();
        }
    }

    private BigDecimal sharesForPnl() {
        if (netPaperShares != null) {
            return netPaperShares;
        }

        return paperShares;
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

    public BigDecimal getPaperSizeUsd() {
        return paperSizeUsd;
    }

    public BigDecimal getPaperShares() {
        return sharesForPnl();
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

    public BigDecimal getGrossPaperShares() {
        return grossPaperShares;
    }

    public BigDecimal getNetPaperShares() {
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

    public BigDecimal getPaperPnl() {
        return paperPnl;
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
