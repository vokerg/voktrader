package com.vokerg.voktrader.marketdata.model;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.marketdata.OrderBookSide;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "market_depth_snapshot_levels")
public class MarketDepthSnapshotLevelEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_entity_id")
    private MarketEntity market;

    private Long marketId;
    private Long remainingSeconds;

    @Column(length = 128)
    private String outcome;

    @Column(length = 128)
    private String tokenId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private OrderBookSide side;

    private Integer levelIndex;

    @Column(precision = 19, scale = 8)
    private BigDecimal price;

    @Column(precision = 19, scale = 8)
    private BigDecimal size;

    private Instant bookUpdatedAt;
    private Instant capturedAt;

    protected MarketDepthSnapshotLevelEntity() {
    }

    public static MarketDepthSnapshotLevelEntity snapshot(
            MarketEntity market,
            Long marketId,
            Long remainingSeconds,
            OutcomeOrderBook book,
            OrderBookSide side,
            int levelIndex,
            OrderBookLevel level,
            Instant capturedAt
    ) {
        MarketDepthSnapshotLevelEntity entity = new MarketDepthSnapshotLevelEntity();
        entity.market = market;
        entity.marketId = marketId;
        entity.remainingSeconds = remainingSeconds;
        entity.outcome = book.outcome();
        entity.tokenId = book.tokenId();
        entity.side = side;
        entity.levelIndex = levelIndex;
        entity.price = level.price();
        entity.size = level.size();
        entity.bookUpdatedAt = book.updatedAt();
        entity.capturedAt = capturedAt;
        return entity;
    }

    public Long getId() { return id; }
    public MarketEntity getMarket() { return market; }
    public Long getMarketId() { return marketId; }
    public Long getRemainingSeconds() { return remainingSeconds; }
    public String getOutcome() { return outcome; }
    public String getTokenId() { return tokenId; }
    public OrderBookSide getSide() { return side; }
    public Integer getLevelIndex() { return levelIndex; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getSize() { return size; }
    public Instant getBookUpdatedAt() { return bookUpdatedAt; }
    public Instant getCapturedAt() { return capturedAt; }
}
