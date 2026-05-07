package com.vokerg.voktrader.trade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import com.vokerg.voktrader.time.TimeMachine;

@Entity
@Table(
        name = "trade_fills",
        indexes = {
                @Index(name = "idx_trade_fills_trade", columnList = "tradeId"),
                @Index(name = "idx_trade_fills_order", columnList = "orderId")
        }
)
public class TradeFillEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tradeId;
    private Long orderId;
    private Long tradeOrderId;
    private String exchangeOrderId;

    @Enumerated(EnumType.STRING)
    private TradeVenue venue;

    @Enumerated(EnumType.STRING)
    private TradeSide side;

    private BigDecimal price;
    private BigDecimal shares;
    private BigDecimal amountUsd;
    private BigDecimal feeUsd;
    private String liquidityRole;

    @Column(columnDefinition = "TEXT")
    private String rawFill;

    private Instant filledAt;
    private Instant occurredAt;
    private Instant receivedAt;
    private Instant createdAt;

    public static TradeFillEntity synthetic(Long tradeId, Long orderId, TradeSide side, BigDecimal price, BigDecimal shares, BigDecimal amountUsd) {
        TradeFillEntity entity = new TradeFillEntity();
        entity.tradeId = tradeId;
        entity.orderId = orderId;
        entity.tradeOrderId = orderId;
        entity.venue = TradeVenue.PAPER_SIM;
        entity.side = side;
        entity.price = price;
        entity.shares = shares;
        entity.amountUsd = amountUsd;
        entity.feeUsd = BigDecimal.ZERO;
        entity.liquidityRole = "SIMULATED";
        entity.rawFill = "{\"synthetic\":true}";
        entity.filledAt = TimeMachine.now();
        entity.occurredAt = entity.filledAt;
        entity.receivedAt = entity.filledAt;
        return entity;
    }

    public static TradeFillEntity polymarket(
            Long tradeId,
            Long orderId,
            String exchangeOrderId,
            TradeSide side,
            BigDecimal price,
            BigDecimal shares,
            BigDecimal amountUsd,
            BigDecimal feeUsd,
            String rawFill
    ) {
        TradeFillEntity entity = new TradeFillEntity();
        entity.tradeId = tradeId;
        entity.orderId = orderId;
        entity.tradeOrderId = orderId;
        entity.exchangeOrderId = exchangeOrderId;
        entity.venue = TradeVenue.POLYMARKET;
        entity.side = side;
        entity.price = price;
        entity.shares = shares;
        entity.amountUsd = amountUsd;
        entity.feeUsd = feeUsd != null ? feeUsd : BigDecimal.ZERO;
        entity.liquidityRole = "TAKER";
        entity.rawFill = rawFill;
        entity.filledAt = TimeMachine.now();
        entity.occurredAt = entity.filledAt;
        entity.receivedAt = entity.filledAt;
        return entity;
    }

    public static TradeFillEntity backtest(
            Long tradeId,
            Long orderId,
            TradeSide side,
            BigDecimal price,
            BigDecimal shares,
            BigDecimal amountUsd,
            BigDecimal feeUsd,
            String liquidityRole,
            String rawFill
    ) {
        TradeFillEntity entity = new TradeFillEntity();
        entity.tradeId = tradeId;
        entity.orderId = orderId;
        entity.tradeOrderId = orderId;
        entity.exchangeOrderId = "backtest-" + orderId;
        entity.venue = TradeVenue.BACKTEST_SIM;
        entity.side = side;
        entity.price = price;
        entity.shares = shares;
        entity.amountUsd = amountUsd;
        entity.feeUsd = feeUsd != null ? feeUsd : BigDecimal.ZERO;
        entity.liquidityRole = liquidityRole == null ? "TAKER" : liquidityRole;
        entity.rawFill = rawFill;
        entity.filledAt = TimeMachine.now();
        entity.occurredAt = entity.filledAt;
        entity.receivedAt = entity.filledAt;
        return entity;
    }

    @PrePersist
    void prePersist() {
        if (this.tradeOrderId == null) {
            this.tradeOrderId = this.orderId;
        }
        if (this.filledAt == null) {
            this.filledAt = TimeMachine.now();
        }
        if (this.occurredAt == null) {
            this.occurredAt = this.filledAt;
        }
        if (this.receivedAt == null) {
            this.receivedAt = this.filledAt;
        }
        this.createdAt = TimeMachine.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTradeId() {
        return tradeId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getTradeOrderId() {
        return tradeOrderId;
    }

    public String getExchangeOrderId() {
        return exchangeOrderId;
    }

    public TradeVenue getVenue() {
        return venue;
    }

    public TradeSide getSide() {
        return side;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getShares() {
        return shares;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public BigDecimal getFeeUsd() {
        return feeUsd;
    }

    public String getLiquidityRole() {
        return liquidityRole;
    }

    public String getRawFill() {
        return rawFill;
    }

    public Instant getFilledAt() {
        return filledAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
