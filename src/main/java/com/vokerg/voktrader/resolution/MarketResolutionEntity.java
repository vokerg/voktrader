package com.vokerg.voktrader.resolution;

import com.vokerg.voktrader.paper.Side;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "market_resolutions")
public class MarketResolutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long marketId;

    @Enumerated(EnumType.STRING)
    private Side winningSide;

    private Instant resolvedAt;

    public Long getId() {
        return id;
    }
}
