package com.vokerg.voktrader.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "voktrader.strategy")
public record StrategyProperties(
        String active,
        Long tickMs,
        SimpleDownCheapTightSpread simpleDownCheapTightSpread,
        BuySellSmoke buySellSmoke,
        CostAwareMomentum costAwareMomentum,
        FlipCatcher flipCatcher,
        OrderBookLiquidity orderBookLiquidity,
        MakerResolutionCarry makerResolutionCarry,
        ResolutionPressureFok resolutionPressureFok
) {

    public static final String DEFAULT_ACTIVE = "cost-aware-momentum";

    public String activeOrDefault() {
        if (active == null || active.isBlank()) {
            return DEFAULT_ACTIVE;
        }
        return active.trim();
    }

    public long tickMsOrDefault() {
        if (tickMs == null || tickMs <= 0) {
            return 1_000L;
        }
        return tickMs;
    }

    public SimpleDownCheapTightSpread simpleDownCheapTightSpreadOrDefault() {
        return simpleDownCheapTightSpread == null
                ? new SimpleDownCheapTightSpread(null, null, null)
                : simpleDownCheapTightSpread;
    }

    public BuySellSmoke buySellSmokeOrDefault() {
        return buySellSmoke == null
                ? new BuySellSmoke(null, null, null, null, null, null)
                : buySellSmoke;
    }

    public CostAwareMomentum costAwareMomentumOrDefault() {
        return costAwareMomentum == null
                ? new CostAwareMomentum(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : costAwareMomentum;
    }

    public FlipCatcher flipCatcherOrDefault() {
        return flipCatcher == null
                ? new FlipCatcher(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : flipCatcher;
    }

    public OrderBookLiquidity orderBookLiquidityOrDefault() {
        return orderBookLiquidity == null
                ? new OrderBookLiquidity(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : orderBookLiquidity;
    }

    public MakerResolutionCarry makerResolutionCarryOrDefault() {
        return makerResolutionCarry == null
                ? new MakerResolutionCarry(null, null, null, null, null, null, null, null, null, null, null, null, null)
                : makerResolutionCarry;
    }

    public ResolutionPressureFok resolutionPressureFokOrDefault() {
        return resolutionPressureFok == null
                ? new ResolutionPressureFok(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : resolutionPressureFok;
    }

    public record SimpleDownCheapTightSpread(
            BigDecimal maxAsk,
            BigDecimal maxSpread,
            BigDecimal orderSizeUsd
    ) {
        public BigDecimal maxAskOrDefault() {
            return maxAsk == null ? new BigDecimal("0.25") : maxAsk;
        }

        public BigDecimal maxSpreadOrDefault() {
            return maxSpread == null ? new BigDecimal("0.03") : maxSpread;
        }

        public BigDecimal orderSizeUsdOrDefault() {
            return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd;
        }
    }

    public record BuySellSmoke(
            String buyOutcome,
            BigDecimal buyBelowAsk,
            BigDecimal buyAboveAsk,
            BigDecimal maxSpread,
            BigDecimal orderSizeUsd,
            BigDecimal minProfitUsd
    ) {
        public String buyOutcomeOrDefault() {
            if (buyOutcome == null || buyOutcome.isBlank()) {
                return "ANY";
            }
            return buyOutcome.trim();
        }

        public BigDecimal buyBelowAskOrDefault() {
            return buyBelowAsk == null ? new BigDecimal("0.25") : buyBelowAsk;
        }

        public BigDecimal buyAboveAskOrDefault() {
            return buyAboveAsk == null ? new BigDecimal("0.75") : buyAboveAsk;
        }

        public BigDecimal maxSpreadOrDefault() {
            return maxSpread == null ? new BigDecimal("0.03") : maxSpread;
        }

        public BigDecimal orderSizeUsdOrDefault() {
            return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd;
        }

        public BigDecimal minProfitUsdOrDefault() {
            return minProfitUsd == null ? new BigDecimal("0.00000001") : minProfitUsd;
        }
    }

    public record CostAwareMomentum(
            Long maxDataAgeMs,
            BigDecimal maxSpread,
            BigDecimal minMid,
            BigDecimal maxEntryAsk,
            BigDecimal minBid,
            BigDecimal maxOppositeMid,
            BigDecimal minMidMove10s,
            BigDecimal minBidMove10s,
            BigDecimal maxNegativeMove3s,
            BigDecimal orderSizeUsd,
            BigDecimal minProfitUsd,
            BigDecimal minPriceMove,
            BigDecimal maxLossUsd,
            BigDecimal stopMid,
            Long minHoldSeconds,
            BigDecimal trailingStopBidDrop,
            Long forceDecisionSeconds,
            Long closedTradeCooldownSeconds,
            Integer maxTradesPerMarket
    ) {
        public long maxDataAgeMsOrDefault() {
            return maxDataAgeMs == null ? 2_500L : maxDataAgeMs;
        }

        public BigDecimal maxSpreadOrDefault() {
            return maxSpread == null ? new BigDecimal("0.02") : maxSpread;
        }

        public BigDecimal minMidOrDefault() {
            return minMid == null ? new BigDecimal("0.58") : minMid;
        }

        public BigDecimal maxEntryAskOrDefault() {
            return maxEntryAsk == null ? new BigDecimal("0.75") : maxEntryAsk;
        }

        public BigDecimal minBidOrDefault() {
            return minBid == null ? new BigDecimal("0.54") : minBid;
        }

        public BigDecimal maxOppositeMidOrDefault() {
            return maxOppositeMid == null ? new BigDecimal("0.43") : maxOppositeMid;
        }

        public BigDecimal minMidMove10sOrDefault() {
            return minMidMove10s == null ? new BigDecimal("0.03") : minMidMove10s;
        }

        public BigDecimal minBidMove10sOrDefault() {
            return minBidMove10s == null ? new BigDecimal("0.02") : minBidMove10s;
        }

        public BigDecimal maxNegativeMove3sOrDefault() {
            return maxNegativeMove3s == null ? new BigDecimal("-0.015") : maxNegativeMove3s;
        }

        public BigDecimal orderSizeUsdOrDefault() {
            return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd;
        }

        public BigDecimal minProfitUsdOrDefault() {
            return minProfitUsd == null ? new BigDecimal("0.10") : minProfitUsd;
        }

        public BigDecimal minPriceMoveOrDefault() {
            return minPriceMove == null ? new BigDecimal("0.05") : minPriceMove;
        }

        public BigDecimal maxLossUsdOrDefault() {
            return maxLossUsd == null ? new BigDecimal("0.15") : maxLossUsd;
        }

        public BigDecimal stopMidOrDefault() {
            return stopMid == null ? new BigDecimal("0.45") : stopMid;
        }

        public long minHoldSecondsOrDefault() {
            return minHoldSeconds == null ? 10L : minHoldSeconds;
        }

        public BigDecimal trailingStopBidDropOrDefault() {
            return trailingStopBidDrop == null ? new BigDecimal("0.02") : trailingStopBidDrop;
        }

        public long forceDecisionSecondsOrDefault() {
            return forceDecisionSeconds == null ? 20L : forceDecisionSeconds;
        }

        public long closedTradeCooldownSecondsOrDefault() {
            return closedTradeCooldownSeconds == null ? 90L : closedTradeCooldownSeconds;
        }

        public int maxTradesPerMarketOrDefault() {
            return maxTradesPerMarket == null ? 5 : maxTradesPerMarket;
        }

    }

    public record FlipCatcher(
            Long maxDataAgeMs,
            BigDecimal maxSpread,
            BigDecimal minCandidateMid,
            BigDecimal maxCandidateMid,
            BigDecimal maxEntryAsk,
            BigDecimal minCandidateMidMove5s,
            BigDecimal minCandidateBidMove5s,
            BigDecimal maxOppositeMidMove5s,
            BigDecimal maxOppositeBidMove5s,
            BigDecimal maxNegativeMove3s,
            BigDecimal orderSizeUsd,
            BigDecimal minProfitUsd,
            BigDecimal minPriceMove,
            BigDecimal maxLossUsd,
            BigDecimal stopMid,
            Long minHoldSeconds,
            BigDecimal trailingStopBidDrop,
            Long forceDecisionSeconds,
            Long closedTradeCooldownSeconds,
            Integer maxTradesPerMarket
    ) {
        public long maxDataAgeMsOrDefault() { return maxDataAgeMs == null ? 2_500L : maxDataAgeMs; }
        public BigDecimal maxSpreadOrDefault() { return maxSpread == null ? new BigDecimal("0.02") : maxSpread; }
        public BigDecimal minCandidateMidOrDefault() { return minCandidateMid == null ? new BigDecimal("0.45") : minCandidateMid; }
        public BigDecimal maxCandidateMidOrDefault() { return maxCandidateMid == null ? new BigDecimal("0.55") : maxCandidateMid; }
        public BigDecimal maxEntryAskOrDefault() { return maxEntryAsk == null ? new BigDecimal("0.56") : maxEntryAsk; }
        public BigDecimal minCandidateMidMove5sOrDefault() { return minCandidateMidMove5s == null ? new BigDecimal("0.035") : minCandidateMidMove5s; }
        public BigDecimal minCandidateBidMove5sOrDefault() { return minCandidateBidMove5s == null ? new BigDecimal("0.025") : minCandidateBidMove5s; }
        public BigDecimal maxOppositeMidMove5sOrDefault() { return maxOppositeMidMove5s == null ? new BigDecimal("-0.025") : maxOppositeMidMove5s; }
        public BigDecimal maxOppositeBidMove5sOrDefault() { return maxOppositeBidMove5s == null ? new BigDecimal("-0.015") : maxOppositeBidMove5s; }
        public BigDecimal maxNegativeMove3sOrDefault() { return maxNegativeMove3s == null ? new BigDecimal("-0.005") : maxNegativeMove3s; }
        public BigDecimal orderSizeUsdOrDefault() { return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd; }
        public BigDecimal minProfitUsdOrDefault() { return minProfitUsd == null ? new BigDecimal("0.10") : minProfitUsd; }
        public BigDecimal minPriceMoveOrDefault() { return minPriceMove == null ? new BigDecimal("0.05") : minPriceMove; }
        public BigDecimal maxLossUsdOrDefault() { return maxLossUsd == null ? new BigDecimal("0.15") : maxLossUsd; }
        public BigDecimal stopMidOrDefault() { return stopMid == null ? new BigDecimal("0.40") : stopMid; }
        public long minHoldSecondsOrDefault() { return minHoldSeconds == null ? 8L : minHoldSeconds; }
        public BigDecimal trailingStopBidDropOrDefault() { return trailingStopBidDrop == null ? new BigDecimal("0.02") : trailingStopBidDrop; }
        public long forceDecisionSecondsOrDefault() { return forceDecisionSeconds == null ? 20L : forceDecisionSeconds; }
        public long closedTradeCooldownSecondsOrDefault() { return closedTradeCooldownSeconds == null ? 90L : closedTradeCooldownSeconds; }
        public int maxTradesPerMarketOrDefault() { return maxTradesPerMarket == null ? 5 : maxTradesPerMarket; }
    }

    public record OrderBookLiquidity(
            Long maxDataAgeMs,
            Long maxBookAgeMs,
            BigDecimal maxSpread,
            BigDecimal maxEntryAsk,
            BigDecimal minBid,
            BigDecimal minMidMove5s,
            BigDecimal maxTakerSlippage,
            BigDecimal maxTakerWorstPrice,
            BigDecimal maxTakerFeeUsd,
            BigDecimal minNearAskDepthShares,
            BigDecimal minNearBidDepthShares,
            BigDecimal minNearDepthImbalance,
            BigDecimal nearTopRange,
            BigDecimal orderSizeUsd,
            BigDecimal minProfitUsd,
            BigDecimal minPriceMove,
            BigDecimal maxLossUsd,
            BigDecimal stopMid,
            Long minHoldSeconds,
            Long closedTradeCooldownSeconds,
            Integer maxTradesPerMarket
    ) {
        public long maxDataAgeMsOrDefault() { return maxDataAgeMs == null ? 1_500L : maxDataAgeMs; }
        public long maxBookAgeMsOrDefault() { return maxBookAgeMs == null ? 1_500L : maxBookAgeMs; }
        public BigDecimal maxSpreadOrDefault() { return maxSpread == null ? new BigDecimal("0.02") : maxSpread; }
        public BigDecimal maxEntryAskOrDefault() { return maxEntryAsk == null ? new BigDecimal("0.62") : maxEntryAsk; }
        public BigDecimal minBidOrDefault() { return minBid == null ? new BigDecimal("0.45") : minBid; }
        public BigDecimal minMidMove5sOrDefault() { return minMidMove5s == null ? new BigDecimal("0.015") : minMidMove5s; }
        public BigDecimal maxTakerSlippageOrDefault() { return maxTakerSlippage == null ? new BigDecimal("0.015") : maxTakerSlippage; }
        public BigDecimal maxTakerWorstPriceOrDefault() { return maxTakerWorstPrice == null ? new BigDecimal("0.65") : maxTakerWorstPrice; }
        public BigDecimal maxTakerFeeUsdOrDefault() { return maxTakerFeeUsd == null ? new BigDecimal("0.04") : maxTakerFeeUsd; }
        public BigDecimal minNearAskDepthSharesOrDefault() { return minNearAskDepthShares == null ? new BigDecimal("2.0") : minNearAskDepthShares; }
        public BigDecimal minNearBidDepthSharesOrDefault() { return minNearBidDepthShares == null ? new BigDecimal("2.0") : minNearBidDepthShares; }
        public BigDecimal minNearDepthImbalanceOrDefault() { return minNearDepthImbalance == null ? new BigDecimal("-0.20") : minNearDepthImbalance; }
        public BigDecimal nearTopRangeOrDefault() { return nearTopRange == null ? new BigDecimal("0.03") : nearTopRange; }
        public BigDecimal orderSizeUsdOrDefault() { return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd; }
        public BigDecimal minProfitUsdOrDefault() { return minProfitUsd == null ? new BigDecimal("0.08") : minProfitUsd; }
        public BigDecimal minPriceMoveOrDefault() { return minPriceMove == null ? new BigDecimal("0.04") : minPriceMove; }
        public BigDecimal maxLossUsdOrDefault() { return maxLossUsd == null ? new BigDecimal("0.12") : maxLossUsd; }
        public BigDecimal stopMidOrDefault() { return stopMid == null ? new BigDecimal("0.43") : stopMid; }
        public long minHoldSecondsOrDefault() { return minHoldSeconds == null ? 6L : minHoldSeconds; }
        public long closedTradeCooldownSecondsOrDefault() { return closedTradeCooldownSeconds == null ? 90L : closedTradeCooldownSeconds; }
        public int maxTradesPerMarketOrDefault() { return maxTradesPerMarket == null ? 5 : maxTradesPerMarket; }
    }

    public record MakerResolutionCarry(
            Long maxDataAgeMs,
            Long maxBookAgeMs,
            BigDecimal maxSpread,
            BigDecimal minBid,
            BigDecimal maxMakerBid,
            BigDecimal minMidMove5s,
            BigDecimal minNearBidDepthShares,
            BigDecimal nearTopRange,
            BigDecimal orderSizeUsd,
            BigDecimal minProfitUsd,
            BigDecimal waitForResolutionBelowExitBid,
            Long waitForResolutionSeconds,
            Integer maxTradesPerMarket
    ) {
        public long maxDataAgeMsOrDefault() { return maxDataAgeMs == null ? 1_500L : maxDataAgeMs; }
        public long maxBookAgeMsOrDefault() { return maxBookAgeMs == null ? 1_500L : maxBookAgeMs; }
        public BigDecimal maxSpreadOrDefault() { return maxSpread == null ? new BigDecimal("0.03") : maxSpread; }
        public BigDecimal minBidOrDefault() { return minBid == null ? new BigDecimal("0.45") : minBid; }
        public BigDecimal maxMakerBidOrDefault() { return maxMakerBid == null ? new BigDecimal("0.58") : maxMakerBid; }
        public BigDecimal minMidMove5sOrDefault() { return minMidMove5s == null ? new BigDecimal("0.010") : minMidMove5s; }
        public BigDecimal minNearBidDepthSharesOrDefault() { return minNearBidDepthShares == null ? new BigDecimal("2.0") : minNearBidDepthShares; }
        public BigDecimal nearTopRangeOrDefault() { return nearTopRange == null ? new BigDecimal("0.03") : nearTopRange; }
        public BigDecimal orderSizeUsdOrDefault() { return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd; }
        public BigDecimal minProfitUsdOrDefault() { return minProfitUsd == null ? new BigDecimal("0.08") : minProfitUsd; }
        public BigDecimal waitForResolutionBelowExitBidOrDefault() { return waitForResolutionBelowExitBid == null ? new BigDecimal("0.35") : waitForResolutionBelowExitBid; }
        public long waitForResolutionSecondsOrDefault() { return waitForResolutionSeconds == null ? 20L : waitForResolutionSeconds; }
        public int maxTradesPerMarketOrDefault() { return maxTradesPerMarket == null ? 3 : maxTradesPerMarket; }
    }

    public record ResolutionPressureFok(
            Long maxDataAgeMs,
            Long maxBookAgeMs,
            Long minSecondsToExpiry,
            Long maxSecondsToExpiry,
            BigDecimal maxSpread,
            BigDecimal minMid,
            BigDecimal maxEntryAsk,
            BigDecimal minBid,
            BigDecimal maxOppositeMid,
            BigDecimal minMidEdge,
            BigDecimal minMidMove5s,
            BigDecimal maxNegativeMove3s,
            BigDecimal maxTakerSlippage,
            BigDecimal maxTakerWorstPrice,
            BigDecimal maxTakerFeeUsd,
            BigDecimal minNearAskDepthShares,
            BigDecimal nearTopRange,
            BigDecimal orderSizeUsd,
            BigDecimal minProfitUsd,
            BigDecimal maxLossUsd,
            Long waitForResolutionSeconds,
            Integer maxTradesPerMarket
    ) {
        public long maxDataAgeMsOrDefault() { return maxDataAgeMs == null ? 1_500L : maxDataAgeMs; }
        public long maxBookAgeMsOrDefault() { return maxBookAgeMs == null ? 1_500L : maxBookAgeMs; }
        public long minSecondsToExpiryOrDefault() { return minSecondsToExpiry == null ? 12L : minSecondsToExpiry; }
        public long maxSecondsToExpiryOrDefault() { return maxSecondsToExpiry == null ? 95L : maxSecondsToExpiry; }
        public BigDecimal maxSpreadOrDefault() { return maxSpread == null ? new BigDecimal("0.025") : maxSpread; }
        public BigDecimal minMidOrDefault() { return minMid == null ? new BigDecimal("0.54") : minMid; }
        public BigDecimal maxEntryAskOrDefault() { return maxEntryAsk == null ? new BigDecimal("0.72") : maxEntryAsk; }
        public BigDecimal minBidOrDefault() { return minBid == null ? new BigDecimal("0.48") : minBid; }
        public BigDecimal maxOppositeMidOrDefault() { return maxOppositeMid == null ? new BigDecimal("0.47") : maxOppositeMid; }
        public BigDecimal minMidEdgeOrDefault() { return minMidEdge == null ? new BigDecimal("0.08") : minMidEdge; }
        public BigDecimal minMidMove5sOrDefault() { return minMidMove5s == null ? new BigDecimal("0.008") : minMidMove5s; }
        public BigDecimal maxNegativeMove3sOrDefault() { return maxNegativeMove3s == null ? new BigDecimal("-0.008") : maxNegativeMove3s; }
        public BigDecimal maxTakerSlippageOrDefault() { return maxTakerSlippage == null ? new BigDecimal("0.020") : maxTakerSlippage; }
        public BigDecimal maxTakerWorstPriceOrDefault() { return maxTakerWorstPrice == null ? new BigDecimal("0.74") : maxTakerWorstPrice; }
        public BigDecimal maxTakerFeeUsdOrDefault() { return maxTakerFeeUsd == null ? new BigDecimal("0.06") : maxTakerFeeUsd; }
        public BigDecimal minNearAskDepthSharesOrDefault() { return minNearAskDepthShares == null ? new BigDecimal("2.0") : minNearAskDepthShares; }
        public BigDecimal nearTopRangeOrDefault() { return nearTopRange == null ? new BigDecimal("0.03") : nearTopRange; }
        public BigDecimal orderSizeUsdOrDefault() { return orderSizeUsd == null ? new BigDecimal("1.00") : orderSizeUsd; }
        public BigDecimal minProfitUsdOrDefault() { return minProfitUsd == null ? new BigDecimal("0.06") : minProfitUsd; }
        public BigDecimal maxLossUsdOrDefault() { return maxLossUsd == null ? new BigDecimal("0.18") : maxLossUsd; }
        public long waitForResolutionSecondsOrDefault() { return waitForResolutionSeconds == null ? 18L : waitForResolutionSeconds; }
        public int maxTradesPerMarketOrDefault() { return maxTradesPerMarket == null ? 3 : maxTradesPerMarket; }
    }
}
