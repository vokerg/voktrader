package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A strategy request that can only reduce exposure through a SELL.
 */
public final class ExitIntent {
    private final TradeIntent tradeIntent;
    private final StrategyInstanceKey owner;

    private ExitIntent(TradeIntent tradeIntent) {
        this.tradeIntent = Objects.requireNonNull(tradeIntent, "tradeIntent is required");
        if (tradeIntent.side() != TradeSide.SELL) {
            throw new IllegalArgumentException("ExitIntent requires SELL side");
        }
        this.owner = StrategyInstanceKey.of(tradeIntent.botId(), tradeIntent.strategyId());
    }

    public static ExitIntent sell(
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal shares,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return new ExitIntent(TradeIntent.sell(market, price, shares, strategyId, ruleId, reason));
    }

    public static ExitIntent sell(
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal shares,
            TradeOrderType orderType,
            boolean postOnly,
            BigDecimal limitPrice,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return new ExitIntent(TradeIntent.sell(
                botId,
                market,
                price,
                shares,
                orderType,
                postOnly,
                limitPrice,
                strategyId,
                ruleId,
                reason
        ));
    }

    TradeIntent tradeIntent() {
        return tradeIntent;
    }

    public StrategyInstanceKey owner() {
        return owner;
    }

    public Long botId() {
        return tradeIntent.botId();
    }

    public String strategyId() {
        return tradeIntent.strategyId();
    }

    public String marketId() {
        return tradeIntent.marketId();
    }

    public TradeSide side() {
        return tradeIntent.side();
    }

    public BigDecimal shares() {
        return tradeIntent.shares();
    }

    public TradeOrderType orderType() {
        return tradeIntent.orderType();
    }

    public boolean postOnly() {
        return tradeIntent.postOnly();
    }

    public BigDecimal limitPrice() {
        return tradeIntent.limitPrice();
    }
}
