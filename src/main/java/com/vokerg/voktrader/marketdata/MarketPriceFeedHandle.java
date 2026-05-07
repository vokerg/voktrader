package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;

public final class MarketPriceFeedHandle implements AutoCloseable {
    private final MarketPriceFeedService owner;
    private final String marketId;
    private final Long botId;
    private final GammaMarketDto market;
    private final LatestPriceState latestPriceState;
    private final OrderBookState orderBookState;
    private boolean closed;

    MarketPriceFeedHandle(
            MarketPriceFeedService owner,
            String marketId,
            Long botId,
            GammaMarketDto market,
            LatestPriceState latestPriceState,
            OrderBookState orderBookState
    ) {
        this.owner = owner;
        this.marketId = marketId;
        this.botId = botId;
        this.market = market;
        this.latestPriceState = latestPriceState;
        this.orderBookState = orderBookState;
    }

    public String marketId() {
        return marketId;
    }

    public Long botId() {
        return botId;
    }

    public GammaMarketDto market() {
        return market;
    }

    public LatestPriceState latestPriceState() {
        return latestPriceState;
    }

    public OrderBookState orderBookState() {
        return orderBookState;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.release(marketId, botId);
    }
}
