package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A strategy request that can only increase exposure through a BUY.
 *
 * The wrapped transport intent is deliberately package-private so strategy code
 * cannot select an executor or gateway after creating an entry request.
 */
public final class EntryIntent {
    private final TradeIntent tradeIntent;
    private final StrategyInstanceKey owner;

    private EntryIntent(TradeIntent tradeIntent) {
        this.tradeIntent = Objects.requireNonNull(tradeIntent, "tradeIntent is required");
        if (tradeIntent.side() != TradeSide.BUY) {
            throw new IllegalArgumentException("EntryIntent requires BUY side");
        }
        this.owner = StrategyInstanceKey.of(tradeIntent.botId(), tradeIntent.strategyId());
    }

    public static EntryIntent buy(
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal amountUsd,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return new EntryIntent(TradeIntent.buy(market, price, amountUsd, strategyId, ruleId, reason));
    }

    public static EntryIntent buy(
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal amountUsd,
            TradeOrderType orderType,
            boolean postOnly,
            BigDecimal limitPrice,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return new EntryIntent(TradeIntent.buy(
                botId,
                market,
                price,
                amountUsd,
                orderType,
                postOnly,
                limitPrice,
                strategyId,
                ruleId,
                reason
        ));
    }

    public static EntryIntent buy(
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal amountUsd,
            BigDecimal shares,
            TradeOrderType orderType,
            boolean postOnly,
            BigDecimal limitPrice,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return new EntryIntent(TradeIntent.buy(
                botId,
                market,
                price,
                amountUsd,
                shares,
                orderType,
                postOnly,
                limitPrice,
                strategyId,
                ruleId,
                reason
        ));
    }

    public EntryIntent withRestingTtlSeconds(Integer restingTtlSeconds) {
        return new EntryIntent(tradeIntent.withRestingTtlSeconds(restingTtlSeconds));
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

    public BigDecimal amountUsd() {
        return tradeIntent.amountUsd();
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

    public Integer restingTtlSeconds() {
        return tradeIntent.restingTtlSeconds();
    }
}
