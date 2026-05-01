package com.vokerg.voktrader.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "voktrader.strategy")
public record StrategyProperties(
        String active,
        Long tickMs,
        SimpleDownCheapTightSpread simpleDownCheapTightSpread,
        BuySellSmoke buySellSmoke,
        CostAwareMomentum costAwareMomentum
) {

    public static final String DEFAULT_ACTIVE = "simple-down-cheap-tight-spread";

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

    public record SimpleDownCheapTightSpread(
            BigDecimal maxAsk,
            BigDecimal maxSpread,
            BigDecimal paperSizeUsd
    ) {
        public BigDecimal maxAskOrDefault() {
            return maxAsk == null ? new BigDecimal("0.25") : maxAsk;
        }

        public BigDecimal maxSpreadOrDefault() {
            return maxSpread == null ? new BigDecimal("0.03") : maxSpread;
        }

        public BigDecimal paperSizeUsdOrDefault() {
            return paperSizeUsd == null ? new BigDecimal("1.00") : paperSizeUsd;
        }
    }

    public record BuySellSmoke(
            String buyOutcome,
            BigDecimal buyBelowAsk,
            BigDecimal buyAboveAsk,
            BigDecimal maxSpread,
            BigDecimal paperSizeUsd,
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

        public BigDecimal paperSizeUsdOrDefault() {
            return paperSizeUsd == null ? new BigDecimal("1.00") : paperSizeUsd;
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
            BigDecimal paperSizeUsd,
            BigDecimal minProfitUsd,
            BigDecimal minPriceMove,
            BigDecimal maxLossUsd,
            BigDecimal stopMid,
            Long minHoldSeconds,
            BigDecimal trailingStopBidDrop,
            Long forceDecisionSeconds,
            Long closedTradeCooldownSeconds,
            Integer maxCompletedTradesPerMarket
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

        public BigDecimal paperSizeUsdOrDefault() {
            return paperSizeUsd == null ? new BigDecimal("1.00") : paperSizeUsd;
        }

        public BigDecimal minProfitUsdOrDefault() {
            return minProfitUsd == null ? new BigDecimal("0.02") : minProfitUsd;
        }

        public BigDecimal minPriceMoveOrDefault() {
            return minPriceMove == null ? new BigDecimal("0.05") : minPriceMove;
        }

        public BigDecimal maxLossUsdOrDefault() {
            return maxLossUsd == null ? new BigDecimal("0.08") : maxLossUsd;
        }

        public BigDecimal stopMidOrDefault() {
            return stopMid == null ? new BigDecimal("0.52") : stopMid;
        }

        public long minHoldSecondsOrDefault() {
            return minHoldSeconds == null ? 10L : minHoldSeconds;
        }

        public BigDecimal trailingStopBidDropOrDefault() {
            return trailingStopBidDrop == null ? new BigDecimal("0.03") : trailingStopBidDrop;
        }

        public long forceDecisionSecondsOrDefault() {
            return forceDecisionSeconds == null ? 20L : forceDecisionSeconds;
        }

        public long closedTradeCooldownSecondsOrDefault() {
            return closedTradeCooldownSeconds == null ? 90L : closedTradeCooldownSeconds;
        }

        public int maxCompletedTradesPerMarketOrDefault() {
            return maxCompletedTradesPerMarket == null ? 3 : maxCompletedTradesPerMarket;
        }
    }
}
