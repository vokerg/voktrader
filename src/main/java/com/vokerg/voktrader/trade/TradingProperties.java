package com.vokerg.voktrader.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "voktrader.trading")
public class TradingProperties {
    /**
     * PAPER keeps the old no-money behavior. LIVE_SHADOW records live-like intents/orders but never submits.
     * LIVE_TINY/LIVE are intentionally blocked by LiveExecutionService until a real executor is wired in.
     */
    private ExecutionMode mode = ExecutionMode.PAPER;

    /** If true, any real-live mode is blocked. LIVE_SHADOW still records the block as a risk check. */
    private boolean killSwitchEnabled = true;

    /** A second explicit switch. Real live modes require liveEnabled=true and killSwitchEnabled=false. */
    private boolean liveEnabled = false;

    private BigDecimal maxOrderUsd = new BigDecimal("1.00");
    private BigDecimal maxSpread = new BigDecimal("0.03");
    private long maxPriceAgeMs = 1500;
    private int maxOpenLiveTrades = 1;
    private int maxTradesPerMarket = 1;
    private int minSecondsToExpiry = 30;
    private BigDecimal paperFeeRate = new BigDecimal("0.072");
    private BigDecimal takerFeeRate = new BigDecimal("0.072");
    private boolean estimateLiveFeesWhenMissing = true;
    private Set<String> allowedStrategyIds = new LinkedHashSet<>(Set.of("simple-down-cheap-tight-spread"));

    public ExecutionMode getMode() {
        return mode;
    }

    public void setMode(ExecutionMode mode) {
        this.mode = mode;
    }

    public boolean isKillSwitchEnabled() {
        return killSwitchEnabled;
    }

    public void setKillSwitchEnabled(boolean killSwitchEnabled) {
        this.killSwitchEnabled = killSwitchEnabled;
    }

    public boolean isLiveEnabled() {
        return liveEnabled;
    }

    public void setLiveEnabled(boolean liveEnabled) {
        this.liveEnabled = liveEnabled;
    }

    public BigDecimal getMaxOrderUsd() {
        return maxOrderUsd;
    }

    public void setMaxOrderUsd(BigDecimal maxOrderUsd) {
        this.maxOrderUsd = maxOrderUsd;
    }

    public BigDecimal getMaxSpread() {
        return maxSpread;
    }

    public void setMaxSpread(BigDecimal maxSpread) {
        this.maxSpread = maxSpread;
    }

    public long getMaxPriceAgeMs() {
        return maxPriceAgeMs;
    }

    public void setMaxPriceAgeMs(long maxPriceAgeMs) {
        this.maxPriceAgeMs = maxPriceAgeMs;
    }

    public int getMaxOpenLiveTrades() {
        return maxOpenLiveTrades;
    }

    public void setMaxOpenLiveTrades(int maxOpenLiveTrades) {
        this.maxOpenLiveTrades = maxOpenLiveTrades;
    }

    public int getMaxTradesPerMarket() {
        return maxTradesPerMarket;
    }

    public void setMaxTradesPerMarket(int maxTradesPerMarket) {
        this.maxTradesPerMarket = maxTradesPerMarket;
    }

    public int getMinSecondsToExpiry() {
        return minSecondsToExpiry;
    }

    public void setMinSecondsToExpiry(int minSecondsToExpiry) {
        this.minSecondsToExpiry = minSecondsToExpiry;
    }

    public BigDecimal getPaperFeeRate() {
        return paperFeeRate;
    }

    public void setPaperFeeRate(BigDecimal paperFeeRate) {
        this.paperFeeRate = paperFeeRate;
    }

    public BigDecimal getTakerFeeRate() {
        return takerFeeRate;
    }

    public void setTakerFeeRate(BigDecimal takerFeeRate) {
        this.takerFeeRate = takerFeeRate;
    }

    public boolean isEstimateLiveFeesWhenMissing() {
        return estimateLiveFeesWhenMissing;
    }

    public void setEstimateLiveFeesWhenMissing(boolean estimateLiveFeesWhenMissing) {
        this.estimateLiveFeesWhenMissing = estimateLiveFeesWhenMissing;
    }

    public Set<String> getAllowedStrategyIds() {
        return allowedStrategyIds;
    }

    public void setAllowedStrategyIds(Set<String> allowedStrategyIds) {
        this.allowedStrategyIds = allowedStrategyIds == null ? new LinkedHashSet<>() : allowedStrategyIds;
    }
}
