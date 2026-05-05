package com.vokerg.voktrader.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "voktrader.market-data.depth-snapshots")
public record MarketDepthSnapshotProperties(
        BigDecimal nearTopRange
) {
    public MarketDepthSnapshotProperties {
        nearTopRange = nearTopRange == null ? new BigDecimal("0.03") : nearTopRange;
    }
}
