package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.paper.FakeSignalService;
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
    private static final BigDecimal FAKE_SIZE_USD = new BigDecimal("1.00");
    private static final String RULE_NAME = "simple-down-cheap-tight-spread";

    private final LatestPriceState latestPriceState;
    private final MarketSelectionProperties marketSelectionProperties;
    private final TrackedMarketState trackedMarketState;
    private final FakeSignalService fakeSignalService;

    private boolean alreadyLoggedDownSignal = false;

    @Scheduled(fixedRate = 1000)
    public void checkForSignal() {
        if (alreadyLoggedDownSignal) {
            return;
        }

        if (!isInsideTradingWindow()) {
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

        var market = trackedMarketState.currentMarket().orElse(null);

        if (market == null) {
            return;
        }

        var created = fakeSignalService.createBuySignal(
                market,
                down,
                FAKE_SIZE_USD,
                RULE_NAME,
                "Down ask <= " + MAX_ASK + " and spread <= " + MAX_SPREAD
        );

        if (created.isPresent()) {
            alreadyLoggedDownSignal = true;
        }
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
