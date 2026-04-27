package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.pricing.LatestPriceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleSignalLogger {

    private static final BigDecimal MAX_ASK = new BigDecimal("0.25");
    private static final BigDecimal MAX_SPREAD = new BigDecimal("0.03");

    private final LatestPriceState latestPriceState;
    private final MarketSelectionProperties marketSelectionProperties;
    private final TrackedMarketState trackedMarketState;

    private boolean alreadyLoggedDownSignal = false;

    @Scheduled(fixedRate = 1000)
    public void checkForSignal() {
        if (!isInsideTradingWindow()) {
            return;
        }
        if (alreadyLoggedDownSignal) {
            return;
        }

        var down = latestPriceState.byOutcome("Down").orElse(null);

        if (down == null || down.ask() == null || down.spread() == null) {
            return;
        }

        boolean askIsCheapEnough = down.ask().compareTo(MAX_ASK) <= 0;
        boolean spreadIsTightEnough = down.spread().compareTo(MAX_SPREAD) <= 0;

        if (!askIsCheapEnough || !spreadIsTightEnough) {
            return;
        }

        alreadyLoggedDownSignal = true;

        Duration remaining = trackedMarketState.currentEndDate()
                .map(endDate -> Duration.between(Instant.now(), endDate))
                .orElse(null);

        log.info(
                "FAKE SIGNAL: BUY outcome={} tokenId={} entryAsk={} spread={} fakeSizeUsd=1.00 remaining={}",
                down.outcome(),
                down.tokenId(),
                down.ask(),
                down.spread(),
                remaining);
    }

    private boolean isInsideTradingWindow() {
        var endDate = trackedMarketState.currentEndDate().orElse(null);

        if (endDate == null) {
            return false;
        }

        Duration remaining = Duration.between(Instant.now(), endDate);

        boolean afterMaxRemaining = remaining.compareTo(marketSelectionProperties.maxRemaining()) <= 0;
        boolean beforeMinRemaining = remaining.compareTo(marketSelectionProperties.minRemaining()) >= 0;

        return afterMaxRemaining && beforeMinRemaining;
    }
}
