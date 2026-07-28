package com.vokerg.voktrader.fee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fee_metadata", indexes = {
        @Index(name = "idx_fee_metadata_market_effective", columnList = "market_id,effective_at")
})
public class FeeMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false, length = 128)
    private String marketId;

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal rate;

    @Column(nullable = false)
    private int exponent;

    @Column(name = "taker_only", nullable = false)
    private boolean takerOnly;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    protected FeeMetadataEntity() {
    }

    public FeeMetadataEntity(FeeMetadata metadata) {
        this.marketId = metadata.marketId();
        this.rate = metadata.rate();
        this.exponent = metadata.exponent();
        this.takerOnly = metadata.takerOnly();
        this.source = metadata.source();
        this.effectiveAt = metadata.effectiveAt();
    }

    public FeeMetadata toValue() {
        return new FeeMetadata(marketId, rate, exponent, takerOnly, source, effectiveAt);
    }
}
