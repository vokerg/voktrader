package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.paper.PaperTradeFeeCalculator;
import com.vokerg.voktrader.paper.SignalEntity;
import com.vokerg.voktrader.paper.SignalService;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostAwareMomentumStrategyTest {

    private final LatestPriceState latestPriceState = mock(LatestPriceState.class);
    private final TrackedMarketState trackedMarketState = mock(TrackedMarketState.class);
    private final StrategyTimeWindow strategyTimeWindow = mock(StrategyTimeWindow.class);
    private final SignalService signalService = mock(SignalService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-04-30T10:00:00Z"));
    private CostAwareMomentumStrategy strategy;
    private GammaMarketDto market;

    @BeforeEach
    void setUp() {
        StrategyProperties properties = new StrategyProperties(
                "cost-aware-momentum-paper",
                1000L,
                null,
                null,
                null
        );
        strategy = new CostAwareMomentumStrategy(
                latestPriceState,
                trackedMarketState,
                strategyTimeWindow,
                signalService,
                properties,
                new PaperTradeFeeCalculator(),
                clock
        );
        market = marketEndingIn(60);

        when(strategyTimeWindow.isInsideTradingWindow()).thenReturn(true);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of());
        when(latestPriceState.byOutcome("Up")).thenReturn(Optional.empty());
        when(latestPriceState.byOutcome("Down")).thenReturn(Optional.empty());
        when(latestPriceState.byTokenId(any())).thenReturn(Optional.empty());
    }

    @Test
    void doesNotBuyWhenOnlyOneSideHasPrices() {
        quote("up", "Up", "0.59", "0.61");
        when(latestPriceState.byOutcome("Up")).thenReturn(Optional.of(price("up", "Up", "0.59", "0.61")));
        when(latestPriceState.byOutcome("Down")).thenReturn(Optional.empty());

        strategy.tick();

        verify(signalService, never()).createPaperBuySignal(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotBuyWhenPricesAreStale() {
        setOutcomePrices(
                priceAt("up", "Up", "0.59", "0.61", clock.instant().minusMillis(3_000)),
                priceAt("down", "Down", "0.39", "0.41", clock.instant().minusMillis(3_000))
        );

        strategy.tick();

        verify(signalService, never()).createPaperBuySignal(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotBuyWhenSpreadIsTooWide() {
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(
                price("up", "Up", "0.58", "0.62"),
                price("down", "Down", "0.39", "0.41")
        );

        strategy.tick();

        verify(signalService, never()).createPaperBuySignal(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotBuyWhenMidSumIsFarFromOne() {
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.50", "0.52")
        );

        strategy.tick();

        verify(signalService, never()).createPaperBuySignal(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotBuyCheapSideWithoutPositiveMomentum() {
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );

        strategy.tick();

        verify(signalService, never()).createPaperBuySignal(any(), any(), any(), any(), any());
    }

    @Test
    void buysStrongerSideWhenMidBidSpreadAndMomentumPassThresholds() {
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        OutcomePrice up = price("up", "Up", "0.59", "0.61");
        setOutcomePrices(up, price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(signalService).createPaperBuySignal(
                eq(market),
                eq(up),
                eq(new BigDecimal("1.00")),
                eq(CostAwareMomentumStrategy.ID),
                any()
        );
    }

    @Test
    void doesNotCreateDuplicateOpenPaperSignalsForSameMarket() {
        SignalEntity open = openSignal("open-token", "Up", "0.50", "2.00000000");
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of(open));
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(price("up", "Up", "0.59", "0.61"), price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(signalService, never()).createPaperBuySignal(any(), any(), any(), any(), any());
    }

    @Test
    void sellsOnTakeProfit() {
        SignalEntity open = openSignal("up", "Up", "0.50", "2.00000000");
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.56", "0.58");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(signalService).sellOpenPaperSignal(open.getId(), up, "cost-aware momentum take profit");
    }

    @Test
    void sellsOnStopLoss() {
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );
        strategy.tick();
        clock.advance(Duration.ofSeconds(11));
        SignalEntity open = openSignal("up", "Up", "0.50", "2.00000000", 12);
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(signalService).sellOpenPaperSignal(open.getId(), up, "cost-aware momentum stop loss");
    }

    @Test
    void doesNotStopLossBeforeMinimumHold() {
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );
        strategy.tick();
        clock.advance(Duration.ofSeconds(4));
        SignalEntity open = openSignal("up", "Up", "0.50", "2.00000000", 4);
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(signalService, never()).sellOpenPaperSignal(any(), any(), any());
    }

    @Test
    void doesNotStopLossWithoutActualMomentumReversal() {
        SignalEntity open = openSignal("up", "Up", "0.50", "2.00000000", 12);
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(signalService, never()).sellOpenPaperSignal(any(), any(), any());
    }

    @Test
    void holdsNearExpiryIfSellingWouldLockLoss() {
        market = marketEndingIn(10);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        SignalEntity open = openSignal("up", "Up", "0.50", "2.00000000");
        when(signalService.openPaperSignals(CostAwareMomentumStrategy.ID)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(signalService, never()).sellOpenPaperSignal(any(), any(), any());
    }

    private void seedMomentum() {
        setOutcomePrices(
                price("up", "Up", "0.53", "0.55"),
                price("down", "Down", "0.45", "0.47")
        );
        strategy.tick();
    }

    private void setOutcomePrices(OutcomePrice up, OutcomePrice down) {
        when(latestPriceState.byOutcome("Up")).thenReturn(Optional.of(up));
        when(latestPriceState.byOutcome("Down")).thenReturn(Optional.of(down));
    }

    private void quote(String tokenId, String outcome, String bid, String ask) {
        OutcomePrice price = price(tokenId, outcome, bid, ask);
        when(latestPriceState.byOutcome(outcome)).thenReturn(Optional.of(price));
    }

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        return priceAt(tokenId, outcome, bid, ask, clock.instant());
    }

    private OutcomePrice priceAt(String tokenId, String outcome, String bid, String ask, Instant updatedAt) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);

        return new OutcomePrice(
                tokenId,
                outcome,
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                updatedAt
        );
    }

    private SignalEntity openSignal(String tokenId, String outcome, String entryPrice, String paperShares) {
        return openSignal(tokenId, outcome, entryPrice, paperShares, 5);
    }

    private SignalEntity openSignal(
            String tokenId,
            String outcome,
            String entryPrice,
            String paperShares,
            long secondsAgo
    ) {
        return SignalEntity.openPaperBuySignal(
                market.id(),
                market.slug(),
                market.question(),
                outcome,
                tokenId,
                new BigDecimal(entryPrice),
                new BigDecimal("1.00"),
                new BigDecimal(paperShares),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(paperShares),
                CostAwareMomentumStrategy.ID,
                "test",
                clock.instant().minusSeconds(secondsAgo),
                market.endDate()
        );
    }

    private GammaMarketDto marketEndingIn(long seconds) {
        return new GammaMarketDto(
                "market-id",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                clock.instant().plusSeconds(seconds),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
