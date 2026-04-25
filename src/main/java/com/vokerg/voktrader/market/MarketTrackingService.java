package com.vokerg.voktrader.market;

import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import org.springframework.stereotype.Service;

@Service
public class MarketTrackingService {

    private final PolymarketWebSocketClient webSocketClient;

    public MarketTrackingService(PolymarketWebSocketClient webSocketClient) {
        this.webSocketClient = webSocketClient;
    }

    public void trackMarket(MarketEntity market) {
        webSocketClient.subscribeToMarket(market.getPolymarketMarketId());
    }
}
