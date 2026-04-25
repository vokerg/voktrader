package com.vokerg.voktrader.pricing;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_snapshots")
public class PriceSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long marketId;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private BigDecimal spread;
    private Instant capturedAt;

    public Long getId() {
        return id;
    }
}
