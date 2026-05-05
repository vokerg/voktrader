package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;

/**
 * Placeholder boundary for deeper order-book monitoring.
 * The shared price feed calls this hook when a market feed is created.
 */
public interface MarketOrderBookMonitor {
    void beginMonitoring(GammaMarketDto market, MarketTokenMap tokenMap);

    void stopMonitoring(String marketId);
}
