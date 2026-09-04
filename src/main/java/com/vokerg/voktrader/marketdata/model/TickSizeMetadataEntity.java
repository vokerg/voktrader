package com.vokerg.voktrader.marketdata.model;

import com.vokerg.voktrader.marketdata.TickSizeMetadata;
import com.vokerg.voktrader.marketdata.TickSizeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "tick_size_metadata",
        indexes = {
                @Index(name = "idx_tick_size_token_effective", columnList = "token_id,effective_at"),
                @Index(name = "idx_tick_size_market_effective", columnList = "market_id,effective_at")
        }
)
public class TickSizeMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_id", nullable = false, length = 128)
    private String tokenId;

    @Column(name = "market_id", length = 128)
    private String marketId;

    @Column(name = "tick_size", nullable = false, precision = 19, scale = 8)
    private BigDecimal tickSize;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TickSizeSource source;

    protected TickSizeMetadataEntity() {
    }

    public static TickSizeMetadataEntity observed(
            String tokenId,
            String marketId,
            BigDecimal tickSize,
            Instant effectiveAt,
            Instant observedAt,
            TickSizeSource source
    ) {
        TickSizeMetadataEntity entity = new TickSizeMetadataEntity();
        entity.tokenId = tokenId;
        entity.marketId = marketId;
        entity.tickSize = tickSize;
        entity.effectiveAt = effectiveAt;
        entity.observedAt = observedAt;
        entity.source = source;
        return entity;
    }

    public TickSizeMetadata toMetadata() {
        return new TickSizeMetadata(tokenId, marketId, tickSize, effectiveAt, observedAt, source);
    }

    public Long getId() {
        return id;
    }

    public String getTokenId() {
        return tokenId;
    }

    public String getMarketId() {
        return marketId;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public TickSizeSource getSource() {
        return source;
    }
}
