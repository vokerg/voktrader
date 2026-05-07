package com.vokerg.voktrader.backtest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_backtest_runs")
public class BacktestRunEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "market_ids", length = 4000)
    private String marketIds;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "snapshot_count")
    private long snapshotCount;

    @Column(name = "trade_count")
    private long tradeCount;

    @Column(name = "closed_trade_count")
    private long closedTradeCount;

    @Column(name = "open_trade_count")
    private long openTradeCount;

    @Column(name = "total_fee_usd", precision = 19, scale = 8)
    private BigDecimal totalFeeUsd = BigDecimal.ZERO;

    @Column(name = "final_pnl_usd", precision = 19, scale = 8)
    private BigDecimal finalPnlUsd = BigDecimal.ZERO;

    protected BacktestRunEntity() {
    }

    public BacktestRunEntity(String id, String strategyId, String marketIds) {
        this.id = id;
        this.strategyId = strategyId;
        this.marketIds = marketIds;
        this.startedAt = Instant.now();
    }

    public void complete(long snapshotCount, BacktestSummary summary) {
        this.completedAt = Instant.now();
        this.snapshotCount = snapshotCount;
        this.tradeCount = summary.tradeCount();
        this.closedTradeCount = summary.closedTradeCount();
        this.openTradeCount = summary.openTradeCount();
        this.totalFeeUsd = summary.totalFeeUsd();
        this.finalPnlUsd = summary.finalPnlUsd();
    }

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    @PreUpdate
    void preUpdate() {
        if (completedAt == null && snapshotCount > 0) {
            completedAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public String getStrategyId() { return strategyId; }
    public String getMarketIds() { return marketIds; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public long getSnapshotCount() { return snapshotCount; }
    public long getTradeCount() { return tradeCount; }
    public long getClosedTradeCount() { return closedTradeCount; }
    public long getOpenTradeCount() { return openTradeCount; }
    public BigDecimal getTotalFeeUsd() { return totalFeeUsd; }
    public BigDecimal getFinalPnlUsd() { return finalPnlUsd; }
}
