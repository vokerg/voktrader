package com.vokerg.voktrader.marketdata.model;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.marketdata.OutcomePrice;

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
import java.time.Instant;

@Entity
@Table(name = "price_snapshots")
public class PriceSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_entity_id")
    private MarketEntity market;

    @Column(name = "bot_id")
    private Long botId;

    private Long marketId;
    private Long remainingSeconds;

    @Column(precision = 19, scale = 8)
    private BigDecimal upBid;

    @Column(precision = 19, scale = 8)
    private BigDecimal upAsk;

    @Column(precision = 19, scale = 8)
    private BigDecimal upSpread;

    @Column(precision = 19, scale = 8)
    private BigDecimal downBid;

    @Column(precision = 19, scale = 8)
    private BigDecimal downAsk;

    @Column(precision = 19, scale = 8)
    private BigDecimal downSpread;

    private Instant capturedAt;

    protected PriceSnapshotEntity() {
    }

    public static PriceSnapshotEntity snapshot(
            MarketEntity market,
            Long botId,
            Long marketId,
            Long remainingSeconds,
            OutcomePrice up,
            OutcomePrice down,
            Instant capturedAt
    ) {
        PriceSnapshotEntity entity = new PriceSnapshotEntity();
        entity.market = market;
        entity.botId = botId;
        entity.marketId = marketId;
        entity.remainingSeconds = remainingSeconds;
        entity.upBid = up.bid();
        entity.upAsk = up.ask();
        entity.upSpread = up.spread();
        entity.downBid = down.bid();
        entity.downAsk = down.ask();
        entity.downSpread = down.spread();
        entity.capturedAt = capturedAt;
        return entity;
    }

    public Long getId() {
        return id;
    }

    public MarketEntity getMarket() {
        return market;
    }

    public Long getBotId() {
        return botId;
    }

    public Long getMarketId() {
        return marketId;
    }

    public Long getRemainingSeconds() {
        return remainingSeconds;
    }

    public BigDecimal getUpBid() {
        return upBid;
    }

    public BigDecimal getUpAsk() {
        return upAsk;
    }

    public BigDecimal getUpSpread() {
        return upSpread;
    }

    public BigDecimal getDownBid() {
        return downBid;
    }

    public BigDecimal getDownAsk() {
        return downAsk;
    }

    public BigDecimal getDownSpread() {
        return downSpread;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
