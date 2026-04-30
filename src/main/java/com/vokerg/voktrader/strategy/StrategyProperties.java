package com.vokerg.voktrader.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "voktrader.strategy")
public record StrategyProperties(
        String active,
        Long tickMs,
        SimpleDownCheapTightSpread simpleDownCheapTightSpread,
        BuySellSmoke buySellSmoke
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
}
