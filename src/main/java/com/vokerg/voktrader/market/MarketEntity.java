package com.vokerg.voktrader.market;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "markets",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_markets_polymarket_market_id",
                        columnNames = "polymarket_market_id"
                )
        }
)
public class MarketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "polymarket_market_id", nullable = false)
    private String polymarketMarketId;

    @Column(name = "condition_id")
    private String conditionId;

    @Column(length = 1000)
    private String question;

    private String slug;

    private Instant endDate;

    private boolean active;

    private boolean closed;

    private boolean acceptingOrders;

    private boolean resolved;

    private String winningOutcome;

    private String winningAssetId;

    private Instant resolvedAt;

    private Instant firstSeenAt;

    private Instant lastSeenAt;

    public Long getId() {
        return id;
    }

    public String getPolymarketMarketId() {
        return polymarketMarketId;
    }

    public void setPolymarketMarketId(String polymarketMarketId) {
        this.polymarketMarketId = polymarketMarketId;
    }

    public String getConditionId() {
        return conditionId;
    }

    public void setConditionId(String conditionId) {
        this.conditionId = conditionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Instant getEndDate() {
        return endDate;
    }

    public void setEndDate(Instant endDate) {
        this.endDate = endDate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public boolean isAcceptingOrders() {
        return acceptingOrders;
    }

    public void setAcceptingOrders(boolean acceptingOrders) {
        this.acceptingOrders = acceptingOrders;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public String getWinningOutcome() {
        return winningOutcome;
    }

    public void setWinningOutcome(String winningOutcome) {
        this.winningOutcome = winningOutcome;
    }

    public String getWinningAssetId() {
        return winningAssetId;
    }

    public void setWinningAssetId(String winningAssetId) {
        this.winningAssetId = winningAssetId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
