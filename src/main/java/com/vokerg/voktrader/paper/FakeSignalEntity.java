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
        name = "fake_signals",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_fake_signal_market_token_rule",
                        columnNames = {"market_id", "token_id", "rule_name"}
                )
        }
)
public class FakeSignalEntity {

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

    @Column(name = "entry_price", nullable = false, precision = 19, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "fake_size_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal fakeSizeUsd;

    @Column(name = "fake_shares", nullable = false, precision = 19, scale = 8)
    private BigDecimal fakeShares;

    @Column(name = "fee_rate", precision = 19, scale = 8)
    private BigDecimal feeRate;

    @Column(name = "entry_fee_usd", precision = 19, scale = 8)
    private BigDecimal entryFeeUsd;

    @Column(name = "gross_fake_shares", precision = 19, scale = 8)
    private BigDecimal grossFakeShares;

    @Column(name = "net_fake_shares", precision = 19, scale = 8)
    private BigDecimal netFakeShares;

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
    private FakeSignalStatus status;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "winning_outcome")
    private String winningOutcome;

    @Column(name = "fake_pnl", precision = 19, scale = 8)
    private BigDecimal fakePnl;

    @Column(name = "exit_price", precision = 19, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "exit_value_usd", precision = 19, scale = 8)
    private BigDecimal exitValueUsd;

    @Column(name = "exit_reason", length = 1000)
    private String exitReason;

    protected FakeSignalEntity() {
    }

    public static FakeSignalEntity openBuySignal(
            String marketId,
            String marketSlug,
            String question,
            String outcome,
            String tokenId,
            BigDecimal entryPrice,
            BigDecimal fakeSizeUsd,
            BigDecimal grossFakeShares,
            BigDecimal feeRate,
            BigDecimal entryFeeUsd,
            BigDecimal netFakeShares,
            String ruleName,
            String reason,
            Instant createdAt,
            Instant marketEndDate
    ) {
        FakeSignalEntity entity = new FakeSignalEntity();
        entity.marketId = marketId;
        entity.marketSlug = marketSlug;
        entity.question = question;
        entity.outcome = outcome;
        entity.tokenId = tokenId;
        entity.entryPrice = entryPrice;
        entity.fakeSizeUsd = fakeSizeUsd;
        entity.fakeShares = netFakeShares;
        entity.feeRate = feeRate;
        entity.entryFeeUsd = entryFeeUsd;
        entity.grossFakeShares = grossFakeShares;
        entity.netFakeShares = netFakeShares;
        entity.ruleName = ruleName;
        entity.reason = reason;
        entity.createdAt = createdAt;
        entity.marketEndDate = marketEndDate;
        entity.status = FakeSignalStatus.OPEN;
        return entity;
    }

    public void sell(BigDecimal exitPrice, String exitReason, Instant soldAt) {
        if (status != FakeSignalStatus.OPEN) {
            throw new IllegalStateException("Only OPEN fake signals can be sold");
        }

        this.exitPrice = exitPrice;
        this.exitValueUsd = sharesForPnl().multiply(exitPrice);
        this.fakePnl = exitValueUsd.subtract(fakeSizeUsd);
        this.exitReason = exitReason;
        this.resolvedAt = soldAt;
        this.status = FakeSignalStatus.SOLD;
    }

    public void resolve(String winningOutcome, Instant resolvedAt) {
        if (status != FakeSignalStatus.OPEN) {
            throw new IllegalStateException("Only OPEN fake signals can be resolved");
        }

        this.winningOutcome = winningOutcome;
        this.resolvedAt = resolvedAt;

        boolean won = this.outcome.equalsIgnoreCase(winningOutcome);

        if (won) {
            this.status = FakeSignalStatus.WON;
            this.fakePnl = sharesForPnl().subtract(fakeSizeUsd);
        } else {
            this.status = FakeSignalStatus.LOST;
            this.fakePnl = fakeSizeUsd.negate();
        }
    }

    private BigDecimal sharesForPnl() {
        if (netFakeShares != null) {
            return netFakeShares;
        }

        return fakeShares;
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

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public BigDecimal getFakeSizeUsd() {
        return fakeSizeUsd;
    }

    public BigDecimal getFakeShares() {
        return sharesForPnl();
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public BigDecimal getEntryFeeUsd() {
        return entryFeeUsd;
    }

    public BigDecimal getGrossFakeShares() {
        return grossFakeShares;
    }

    public BigDecimal getNetFakeShares() {
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

    public FakeSignalStatus getStatus() {
        return status;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getWinningOutcome() {
        return winningOutcome;
    }

    public BigDecimal getFakePnl() {
        return fakePnl;
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
