package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.paper.SignalEntity;
import com.vokerg.voktrader.paper.SignalService;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BuySellSmokeStrategy implements TradingStrategy {

    public static final String ID = "buy-sell-smoke";

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final SignalService signalService;
    private final StrategyProperties strategyProperties;
    private final StrategyTimeWindow strategyTimeWindow;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        if (!strategyTimeWindow.isInsideTradingWindow()) {
            return;
        }

        trySellOpenSignals();
        tryBuySignal();
    }

    private void trySellOpenSignals() {
        var market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        var config = strategyProperties.buySellSmokeOrDefault();

        for (SignalEntity signal : signalService.openPaperSignals(ID)) {
            if (!market.id().equals(signal.getMarketId())) {
                continue;
            }

            OutcomePrice price = latestPriceState
                    .byTokenId(signal.getTokenId())
                    .orElse(null);

            if (price == null || price.bid() == null) {
                continue;
            }

            BigDecimal exitValueUsd = signal.getPaperShares().multiply(price.bid());
            BigDecimal paperPnl = exitValueUsd.subtract(signal.getPaperSizeUsd());

            if (paperPnl.compareTo(config.minProfitUsdOrDefault()) < 0) {
                continue;
            }

            signalService.sellOpenPaperSignal(
                    signal.getId(),
                    price,
                    "bid produced paper pnl >= " + config.minProfitUsdOrDefault()
            );
        }
    }

    private void tryBuySignal() {
        var market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        boolean alreadyHasOpenSignalForThisMarket = signalService.openPaperSignals(ID)
                .stream()
                .anyMatch(signal -> market.id().equals(signal.getMarketId()));

        if (alreadyHasOpenSignalForThisMarket) {
            return;
        }

        OutcomePrice candidate = findBuyCandidate().orElse(null);

        if (candidate == null) {
            return;
        }

        var config = strategyProperties.buySellSmokeOrDefault();

        signalService.createPaperBuySignal(
                market,
                candidate,
                config.paperSizeUsdOrDefault(),
                ID,
                "ask <= " + config.buyBelowAskOrDefault()
                        + " or ask >= " + config.buyAboveAskOrDefault()
                        + ", spread <= " + config.maxSpreadOrDefault()
        );
    }

    private Optional<OutcomePrice> findBuyCandidate() {
        var config = strategyProperties.buySellSmokeOrDefault();
        String configuredOutcome = config.buyOutcomeOrDefault();

        return latestPriceState
                .allByTokenId()
                .values()
                .stream()
                .filter(price -> outcomeMatches(price, configuredOutcome))
                .filter(this::hasUsableBuyPrice)
                .filter(price -> matchesBuyThreshold(price, config))
                .filter(price -> hasTightSpread(price, config))
                .min(Comparator.comparing(OutcomePrice::ask));
    }

    private boolean outcomeMatches(OutcomePrice price, String configuredOutcome) {
        return "ANY".equalsIgnoreCase(configuredOutcome)
                || price.outcome().equalsIgnoreCase(configuredOutcome);
    }

    private boolean hasUsableBuyPrice(OutcomePrice price) {
        return price.ask() != null
                && price.ask().compareTo(BigDecimal.ZERO) > 0
                && price.spread() != null;
    }

    private boolean matchesBuyThreshold(
            OutcomePrice price,
            StrategyProperties.BuySellSmoke config
    ) {
        boolean below = price.ask().compareTo(config.buyBelowAskOrDefault()) <= 0;
        boolean above = price.ask().compareTo(config.buyAboveAskOrDefault()) >= 0;

        return below || above;
    }

    private boolean hasTightSpread(
            OutcomePrice price,
            StrategyProperties.BuySellSmoke config
    ) {
        return price.spread().compareTo(config.maxSpreadOrDefault()) <= 0;
    }
}
