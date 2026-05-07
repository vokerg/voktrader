package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;

@FunctionalInterface
public interface MarketResolutionListener {
    void onMarketResolved(String subscriptionMarketId, MarketWsMessageDto message);
}
