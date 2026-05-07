package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.time.TimeMachine;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record TradeIntent(
        Long botId,
        String strategyId,
        String ruleId,
        String marketId,
        String marketSlug,
        String question,
        String conditionId,
        String tokenId,
        String outcome,
        TradeSide side,
        BigDecimal amountUsd,
        BigDecimal shares,
        TradeOrderType orderType,
        boolean postOnly,
        BigDecimal limitPrice,
        BigDecimal observedBid,
        BigDecimal observedAsk,
        BigDecimal observedSpread,
        BigDecimal observedMidpoint,
        Instant priceUpdatedAt,
        Long priceAgeMs,
        Instant decisionAt,
        Instant marketEndAt,
        Long secondsToExpiryAtDecision,
        String reason
) {
    public TradeIntent {
        Objects.requireNonNull(strategyId, "strategyId is required");
        Objects.requireNonNull(marketId, "marketId is required");
        Objects.requireNonNull(tokenId, "tokenId is required");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(side, "side is required");
        decisionAt = decisionAt == null ? Instant.now() : decisionAt;
        orderType = orderType == null ? TradeOrderType.FOK : orderType;
    }

    public BigDecimal expectedPrice() {
        if (limitPrice != null) {
            return limitPrice;
        }
        return side == TradeSide.BUY ? observedAsk : observedBid;
    }

    public LiquidityRole expectedLiquidityRole() {
        if (postOnly) {
            return LiquidityRole.MAKER;
        }
        return orderType.expectsImmediateFill() ? LiquidityRole.TAKER : LiquidityRole.MAKER;
    }

    public static TradeIntent buy(GammaMarketDto market, OutcomePrice price, BigDecimal amountUsd, String strategyId, String ruleId, String reason) {
        return buy(BotRuntimeContextHolder.currentBotId().orElse(null), market, price, amountUsd, strategyId, ruleId, reason);
    }

    public static TradeIntent buy(Long botId, GammaMarketDto market, OutcomePrice price, BigDecimal amountUsd, String strategyId, String ruleId, String reason) {
        return buy(botId, market, price, amountUsd, TradeOrderType.FOK, price.ask(), strategyId, ruleId, reason);
    }

    public static TradeIntent buyMaker(GammaMarketDto market, OutcomePrice price, BigDecimal amountUsd, String strategyId, String ruleId, String reason) {
        return buyMaker(BotRuntimeContextHolder.currentBotId().orElse(null), market, price, amountUsd, strategyId, ruleId, reason);
    }

    public static TradeIntent buyMaker(Long botId, GammaMarketDto market, OutcomePrice price, BigDecimal amountUsd, String strategyId, String ruleId, String reason) {
        return buy(botId, market, price, amountUsd, TradeOrderType.GTC, true, price.bid(), strategyId, ruleId, reason);
    }

    public static TradeIntent buy(
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal amountUsd,
            TradeOrderType orderType,
            BigDecimal limitPrice,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return buy(botId, market, price, amountUsd, orderType, orderType.prefersMaker(), limitPrice, strategyId, ruleId, reason);
    }

    public static TradeIntent buy(
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
        Instant now = TimeMachine.now();
        Instant updatedAt = price.updatedAt();
        Long ageMs = updatedAt == null ? null : Duration.between(updatedAt, now).toMillis();
        Long secondsToExpiry = market.endDate() == null ? null : Duration.between(now, market.endDate()).toSeconds();
        BigDecimal midpoint = midpoint(price.bid(), price.ask());
        return new TradeIntent(
                botId,
                strategyId,
                ruleId,
                market.id(),
                market.slug(),
                market.question(),
                null,
                price.tokenId(),
                price.outcome(),
                TradeSide.BUY,
                amountUsd,
                null,
                orderType,
                postOnly,
                limitPrice,
                price.bid(),
                price.ask(),
                price.spread(),
                midpoint,
                updatedAt,
                ageMs,
                now,
                market.endDate(),
                secondsToExpiry,
                reason
        );
    }

    public static TradeIntent sell(GammaMarketDto market, OutcomePrice price, BigDecimal shares, String strategyId, String ruleId, String reason) {
        return sell(BotRuntimeContextHolder.currentBotId().orElse(null), market, price, shares, strategyId, ruleId, reason);
    }

    public static TradeIntent sell(Long botId, GammaMarketDto market, OutcomePrice price, BigDecimal shares, String strategyId, String ruleId, String reason) {
        return sell(botId, market, price, shares, TradeOrderType.FOK, price.bid(), strategyId, ruleId, reason);
    }

    public static TradeIntent sellMaker(GammaMarketDto market, OutcomePrice price, BigDecimal shares, String strategyId, String ruleId, String reason) {
        return sellMaker(BotRuntimeContextHolder.currentBotId().orElse(null), market, price, shares, strategyId, ruleId, reason);
    }

    public static TradeIntent sellMaker(Long botId, GammaMarketDto market, OutcomePrice price, BigDecimal shares, String strategyId, String ruleId, String reason) {
        return sell(botId, market, price, shares, TradeOrderType.GTC, true, price.ask(), strategyId, ruleId, reason);
    }

    public static TradeIntent sell(
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal shares,
            TradeOrderType orderType,
            BigDecimal limitPrice,
            String strategyId,
            String ruleId,
            String reason
    ) {
        return sell(botId, market, price, shares, orderType, orderType.prefersMaker(), limitPrice, strategyId, ruleId, reason);
    }

    public static TradeIntent sell(
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
        Instant now = TimeMachine.now();
        Instant updatedAt = price.updatedAt();
        Long ageMs = updatedAt == null ? null : Duration.between(updatedAt, now).toMillis();
        Long secondsToExpiry = market.endDate() == null ? null : Duration.between(now, market.endDate()).toSeconds();
        BigDecimal midpoint = midpoint(price.bid(), price.ask());
        BigDecimal amountUsd = limitPrice == null || shares == null ? null : shares.multiply(limitPrice);
        return new TradeIntent(
                botId,
                strategyId,
                ruleId,
                market.id(),
                market.slug(),
                market.question(),
                null,
                price.tokenId(),
                price.outcome(),
                TradeSide.SELL,
                amountUsd,
                shares,
                orderType,
                postOnly,
                limitPrice,
                price.bid(),
                price.ask(),
                price.spread(),
                midpoint,
                updatedAt,
                ageMs,
                now,
                market.endDate(),
                secondsToExpiry,
                reason
        );
    }

    private static BigDecimal midpoint(BigDecimal bid, BigDecimal ask) {
        if (bid == null || ask == null) {
            return null;
        }
        return bid.add(ask).divide(new BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP);
    }
}
