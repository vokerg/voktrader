package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.springframework.stereotype.Component;

@Component
public class NoopMarketOrderBookMonitor implements MarketOrderBookMonitor {
    @Override
    public void beginMonitoring(GammaMarketDto market, MarketTokenMap tokenMap) {
    }

    @Override
    public void stopMonitoring(String marketId) {
    }
}
