package com.vokerg.voktrader.paper;

import jakarta.persistence.*;
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

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "market_end_date")
    private Instant marketEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FakeSignalStatus status;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "winning_outcome")
    private String winningOutcome;

    @Column(name = "fake_pnl", precision = 19, scale = 8)
    private BigDecimal fakePnl;

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
            BigDecimal fakeShares,
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
        entity.fakeShares = fakeShares;
        entity.ruleName = ruleName;
        entity.reason = reason;
        entity.createdAt = createdAt;
        entity.marketEndDate = marketEndDate;
        entity.status = FakeSignalStatus.OPEN;
        return entity;
    }

    public void resolve(String winningOutcome, Instant resolvedAt) {
        this.winningOutcome = winningOutcome;
        this.resolvedAt = resolvedAt;

        boolean won = this.outcome.equalsIgnoreCase(winningOutcome);

        if (won) {
            this.status = FakeSignalStatus.WON;
            this.fakePnl = fakeShares.multiply(BigDecimal.ONE.subtract(entryPrice));
        } else {
            this.status = FakeSignalStatus.LOST;
            this.fakePnl = fakeSizeUsd.negate();
        }
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
        return fakeShares;
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
}