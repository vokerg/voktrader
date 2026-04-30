package com.vokerg.voktrader.trade;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_fills", indexes = {
        @Index(name = "idx_trade_fills_trade", columnList = "trade_id"),
        @Index(name = "idx_trade_fills_order", columnList = "trade_order_id"),
        @Index(name = "idx_trade_fills_exchange_trade", columnList = "exchange_trade_id")
})
public class TradeFillEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    @Column(name = "trade_order_id", nullable = false)
    private Long tradeOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "venue", nullable = false, length = 32)
    private TradeVenue venue;

    @Column(name = "exchange_trade_id")
    private String exchangeTradeId;

    @Column(name = "exchange_order_id")
    private String exchangeOrderId;

    @Column(name = "transaction_hash")
    private String transactionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private TradeSide side;

    @Column(name = "price", nullable = false, precision = 19, scale = 8)
    private BigDecimal price;

    @Column(name = "shares", nullable = false, precision = 19, scale = 8)
    private BigDecimal shares;

    @Column(name = "amount_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal amountUsd;

    @Column(name = "fee_usd", precision = 19, scale = 8)
    private BigDecimal feeUsd;

    @Column(name = "liquidity_role")
    private String liquidityRole;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "raw_fill", columnDefinition = "TEXT")
    private String rawFill;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TradeFillEntity() {
    }

    public static TradeFillEntity synthetic(Long tradeId, Long orderId, TradeSide side, BigDecimal price, BigDecimal shares, BigDecimal amountUsd, BigDecimal feeUsd) {
        TradeFillEntity entity = new TradeFillEntity();
        entity.tradeId = tradeId;
        entity.tradeOrderId = orderId;
        entity.venue = TradeVenue.PAPER_SIM;
        entity.side = side;
        entity.price = price;
        entity.shares = shares;
        entity.amountUsd = amountUsd;
        entity.feeUsd = feeUsd;
        entity.liquidityRole = "TAKER_SIM";
        entity.occurredAt = Instant.now();
        entity.receivedAt = entity.occurredAt;
        entity.rawFill = "{\"synthetic\":true}";
        entity.createdAt = entity.occurredAt;
        return entity;
    }

    public Long getId() { return id; }
    public Long getTradeId() { return tradeId; }
    public Long getTradeOrderId() { return tradeOrderId; }
    public TradeVenue getVenue() { return venue; }
    public String getExchangeTradeId() { return exchangeTradeId; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public String getTransactionHash() { return transactionHash; }
    public TradeSide getSide() { return side; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getShares() { return shares; }
    public BigDecimal getAmountUsd() { return amountUsd; }
    public BigDecimal getFeeUsd() { return feeUsd; }
    public String getLiquidityRole() { return liquidityRole; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getRawFill() { return rawFill; }
    public Instant getCreatedAt() { return createdAt; }
}
