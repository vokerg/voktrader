package com.vokerg.voktrader.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.vokerg.voktrader.trade.model.ExecutionMode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "voktrader.trading")
public class TradingProperties {
    /**
     * Selects the execution source of truth for this process. Strategies do not choose this.
     */
    private ExecutionMode mode = ExecutionMode.PAPER;

    /** If true, real-live execution is blocked. */
    private boolean killSwitchEnabled = true;

    /** A second explicit switch. Real live modes require liveEnabled=true and killSwitchEnabled=false. */
    private boolean liveEnabled = false;

    /**
     * Operator-declared account identity that must be repeated when creating a short-lived live arm.
     */
    private String expectedAccountId = "";

    /**
     * Maximum lifetime of one explicit live arm. Every arm expires automatically.
     */
    private Duration liveArmTtl = Duration.ofMinutes(15);

    private BigDecimal maxOrderUsd = new BigDecimal("1.00");
    private BigDecimal minMakerOrderShares = new BigDecimal("5.00");
    private BigDecimal maxSpread = new BigDecimal("0.03");
    private long maxPriceAgeMs = 1500;
    private int maxOpenLiveTrades = 1;

    /** Block another inner strategy while any entry or position is active for the same bot and market. */
    private boolean onePositionPerBotMarket = true;

    /** Block another entry while any entry or position is active for the same bot, market, and token. */
    private boolean onePositionPerToken = true;

    /** Active exposure cap for one bot/account/mode and market. Values <= 0 disable this cap. */
    private int maxActivePositionsPerMarket = 1;

    /** Active exposure cap for one bot/account/mode across markets. Values <= 0 disable this cap. */
    private int maxActivePositionsPerPortfolio = 0;

    /** Attempt throttle; unlike active-position caps, this considers recent failed/rejected live entry orders. */
    private long liveRetryCooldownSeconds = 0;
    private int minSecondsToExpiry = 30;
    private BigDecimal paperFeeRate = new BigDecimal("0.072");
    private BigDecimal makerFeeRate = BigDecimal.ZERO;
    private BigDecimal takerFeeRate = new BigDecimal("0.072");
    private boolean estimateLiveFeesWhenMissing = true;
    private Set<String> allowedStrategyIds = new LinkedHashSet<>(Set.of(
            "cost-aware-momentum",
            "flip-catcher-reversal"
    ));

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

    public String getExpectedAccountId() {
        return expectedAccountId;
    }

    public void setExpectedAccountId(String expectedAccountId) {
        this.expectedAccountId = expectedAccountId;
    }

    public Duration getLiveArmTtl() {
        return liveArmTtl;
    }

    public void setLiveArmTtl(Duration liveArmTtl) {
        this.liveArmTtl = liveArmTtl;
    }

    public BigDecimal getMaxOrderUsd() {
        return maxOrderUsd;
    }

    public void setMaxOrderUsd(BigDecimal maxOrderUsd) {
        this.maxOrderUsd = maxOrderUsd;
    }

    public BigDecimal getMinMakerOrderShares() {
        return minMakerOrderShares;
    }

    public void setMinMakerOrderShares(BigDecimal minMakerOrderShares) {
        this.minMakerOrderShares = minMakerOrderShares;
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

    public boolean isOnePositionPerBotMarket() {
        return onePositionPerBotMarket;
    }

    public void setOnePositionPerBotMarket(boolean onePositionPerBotMarket) {
        this.onePositionPerBotMarket = onePositionPerBotMarket;
    }

    public boolean isOnePositionPerToken() {
        return onePositionPerToken;
    }

    public void setOnePositionPerToken(boolean onePositionPerToken) {
        this.onePositionPerToken = onePositionPerToken;
    }

    public int getMaxActivePositionsPerMarket() {
        return maxActivePositionsPerMarket;
    }

    public void setMaxActivePositionsPerMarket(int maxActivePositionsPerMarket) {
        this.maxActivePositionsPerMarket = maxActivePositionsPerMarket;
    }

    public int getMaxActivePositionsPerPortfolio() {
        return maxActivePositionsPerPortfolio;
    }

    public void setMaxActivePositionsPerPortfolio(int maxActivePositionsPerPortfolio) {
        this.maxActivePositionsPerPortfolio = maxActivePositionsPerPortfolio;
    }

    /**
     * Compatibility alias for the old ambiguous name. This is an active-position cap, not a cumulative attempt cap.
     */
    @Deprecated
    public int getMaxTradesPerMarket() {
        return maxActivePositionsPerMarket;
    }

    /** Compatibility binder for existing `max-trades-per-market` configuration. */
    @Deprecated
    public void setMaxTradesPerMarket(int maxTradesPerMarket) {
        this.maxActivePositionsPerMarket = maxTradesPerMarket;
    }

    public long getLiveRetryCooldownSeconds() {
        return liveRetryCooldownSeconds;
    }

    public void setLiveRetryCooldownSeconds(long liveRetryCooldownSeconds) {
        this.liveRetryCooldownSeconds = liveRetryCooldownSeconds;
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

    public BigDecimal getMakerFeeRate() {
        return makerFeeRate;
    }

    public void setMakerFeeRate(BigDecimal makerFeeRate) {
        this.makerFeeRate = makerFeeRate;
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
