package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.market.MarketEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "market_depth_snapshots")
public class MarketDepthSnapshotEntity {
    private static final int SCORE_SCALE = 8;

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

    @Column(precision = 19, scale = 8)
    private BigDecimal bestBid;

    @Column(precision = 19, scale = 8)
    private BigDecimal bestAsk;

    @Column(precision = 19, scale = 8)
    private BigDecimal spread;

    @Column(precision = 19, scale = 8)
    private BigDecimal bidDepth;

    @Column(precision = 19, scale = 8)
    private BigDecimal askDepth;

    @Column(precision = 19, scale = 8)
    private BigDecimal nearBidDepth;

    @Column(precision = 19, scale = 8)
    private BigDecimal nearAskDepth;

    @Column(precision = 19, scale = 8)
    private BigDecimal depthImbalance;

    @Column(precision = 19, scale = 8)
    private BigDecimal nearDepthImbalance;

    @Column(precision = 19, scale = 8)
    private BigDecimal estimateBuyUsd;

    @Column(precision = 19, scale = 8)
    private BigDecimal estimateBuyFilledShares;

    @Column(precision = 19, scale = 8)
    private BigDecimal estimateBuyAveragePrice;

    @Column(precision = 19, scale = 8)
    private BigDecimal estimateBuyWorstPrice;

    private Boolean estimateBuyComplete;
    private Integer estimateBuyLevelsConsumed;
    private Long bookAgeMs;
    private Boolean stale;
    private Instant bookUpdatedAt;
    private Instant capturedAt;

    protected MarketDepthSnapshotEntity() {
    }

    public static MarketDepthSnapshotEntity snapshot(
            MarketEntity market,
            Long marketId,
            Long remainingSeconds,
            OutcomeOrderBook book,
            BigDecimal nearTopRange,
            BigDecimal estimateBuyUsd,
            long staleAfterMs,
            Instant capturedAt
    ) {
        MarketDepthSnapshotEntity entity = new MarketDepthSnapshotEntity();
        entity.market = market;
        entity.marketId = marketId;
        entity.remainingSeconds = remainingSeconds;
        entity.outcome = book.outcome();
        entity.tokenId = book.tokenId();
        entity.bestBid = book.bestBid().map(OrderBookLevel::price).orElse(null);
        entity.bestAsk = book.bestAsk().map(OrderBookLevel::price).orElse(null);
        entity.spread = book.spread().orElse(null);
        entity.bidDepth = book.bidDepth();
        entity.askDepth = book.askDepth();
        entity.nearBidDepth = book.bidDepthWithin(nearTopRange);
        entity.nearAskDepth = book.askDepthWithin(nearTopRange);
        entity.depthImbalance = imbalance(entity.bidDepth, entity.askDepth);
        entity.nearDepthImbalance = imbalance(entity.nearBidDepth, entity.nearAskDepth);
        FillEstimate estimate = book.estimateBuyUsd(estimateBuyUsd);
        entity.estimateBuyUsd = estimateBuyUsd;
        entity.estimateBuyFilledShares = estimate.filledShares();
        entity.estimateBuyAveragePrice = estimate.averagePrice();
        entity.estimateBuyWorstPrice = estimate.worstPrice();
        entity.estimateBuyComplete = estimate.complete();
        entity.estimateBuyLevelsConsumed = estimate.levelsConsumed();
        entity.bookUpdatedAt = book.updatedAt();
        entity.capturedAt = capturedAt;
        entity.bookAgeMs = book.updatedAt() == null || capturedAt == null
                ? null
                : Duration.between(book.updatedAt(), capturedAt).toMillis();
        entity.stale = entity.bookAgeMs == null || entity.bookAgeMs > staleAfterMs;
        return entity;
    }

    private static BigDecimal imbalance(BigDecimal bidDepth, BigDecimal askDepth) {
        BigDecimal bid = bidDepth == null ? BigDecimal.ZERO : bidDepth;
        BigDecimal ask = askDepth == null ? BigDecimal.ZERO : askDepth;
        BigDecimal total = bid.add(ask);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return bid.subtract(ask).divide(total, SCORE_SCALE, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public MarketEntity getMarket() { return market; }
    public Long getMarketId() { return marketId; }
    public Long getRemainingSeconds() { return remainingSeconds; }
    public String getOutcome() { return outcome; }
    public String getTokenId() { return tokenId; }
    public BigDecimal getBestBid() { return bestBid; }
    public BigDecimal getBestAsk() { return bestAsk; }
    public BigDecimal getSpread() { return spread; }
    public BigDecimal getBidDepth() { return bidDepth; }
    public BigDecimal getAskDepth() { return askDepth; }
    public BigDecimal getNearBidDepth() { return nearBidDepth; }
    public BigDecimal getNearAskDepth() { return nearAskDepth; }
    public BigDecimal getDepthImbalance() { return depthImbalance; }
    public BigDecimal getNearDepthImbalance() { return nearDepthImbalance; }
    public BigDecimal getEstimateBuyUsd() { return estimateBuyUsd; }
    public BigDecimal getEstimateBuyFilledShares() { return estimateBuyFilledShares; }
    public BigDecimal getEstimateBuyAveragePrice() { return estimateBuyAveragePrice; }
    public BigDecimal getEstimateBuyWorstPrice() { return estimateBuyWorstPrice; }
    public Boolean getEstimateBuyComplete() { return estimateBuyComplete; }
    public Integer getEstimateBuyLevelsConsumed() { return estimateBuyLevelsConsumed; }
    public Long getBookAgeMs() { return bookAgeMs; }
    public Boolean getStale() { return stale; }
    public Instant getBookUpdatedAt() { return bookUpdatedAt; }
    public Instant getCapturedAt() { return capturedAt; }
}
