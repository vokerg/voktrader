package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookState;

public record BotRuntimeContext(
        Long botId,
        MarketFamily marketFamily,
        String strategyId,
        TrackedMarketState trackedMarketState,
        LatestPriceState latestPriceState,
        OrderBookState orderBookState
) {
}
