package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
<<<<<<< Updated upstream
import com.vokerg.voktrader.marketdata.OrderBookState;
=======
>>>>>>> Stashed changes

public record BotRuntimeContext(
        Long botId,
        MarketFamily marketFamily,
        String strategyId,
        TrackedMarketState trackedMarketState,
        LatestPriceState latestPriceState,
        OrderBookState orderBookState
) {
}
