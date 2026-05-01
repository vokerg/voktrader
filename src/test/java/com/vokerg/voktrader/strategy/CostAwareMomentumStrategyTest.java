package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.PaperFeeCalculator;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderStatus;
import com.vokerg.voktrader.trade.TradeRepository;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.TradingProperties;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostAwareMomentumStrategyTest {

    private final LatestPriceState latestPriceState = mock(LatestPriceState.class);
    private final TrackedMarketState trackedMarketState = mock(TrackedMarketState.class);
    private final StrategyTimeWindow strategyTimeWindow = mock(StrategyTimeWindow.class);
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
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
                executionRouter,
                tradeRepository,
                properties,
                new PaperFeeCalculator(),
                new TradingProperties(),
                clock
        );
        market = marketEndingIn(60);

        when(strategyTimeWindow.isInsideTradingWindow()).thenReturn(true);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
                CostAwareMomentumStrategy.ID,
                market.id(),
                TradeStatus.OPEN
        )).thenReturn(Optional.empty());
        when(tradeRepository.countByStrategyIdAndMarketIdAndStatusIn(
                any(),
                any(),
                any()
        )).thenReturn(0L);
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                any(),
                any(),
                any()
        )).thenReturn(Optional.empty());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(Optional.empty());
        when(executionRouter.route(any(TradeIntent.class))).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER,
                1L,
                1L,
                TradeStatus.OPEN,
                TradeOrderStatus.FILLED,
                "accepted"
        ));
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

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotBuyWhenPricesAreStale() {
        setOutcomePrices(
                priceAt("up", "Up", "0.59", "0.61", clock.instant().minusMillis(3_000)),
                priceAt("down", "Down", "0.39", "0.41", clock.instant().minusMillis(3_000))
        );

        strategy.tick();

        verify(executionRouter, never()).route(any());
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

        verify(executionRouter, never()).route(any());
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

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotBuyCheapSideWithoutPositiveMomentum() {
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void buysStrongerSideWhenMidBidSpreadAndMomentumPassThresholds() {
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        OutcomePrice up = price("up", "Up", "0.59", "0.61");
        setOutcomePrices(up, price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(executionRouter).route(any(TradeIntent.class));
    }

    @Test
    void doesNotCreateDuplicateOpenTradeForSameMarket() {
        TradeEntity open = openTrade("open-token", "Up", "0.50", "2.00000000");
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
                CostAwareMomentumStrategy.ID,
                market.id(),
                TradeStatus.OPEN
        )).thenReturn(Optional.of(open));
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(price("up", "Up", "0.59", "0.61"), price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotReenterMarketAfterCompletedTradeLimitReached() {
        when(tradeRepository.countByStrategyIdAndMarketIdAndStatusIn(
                any(),
                any(),
                any()
        )).thenReturn(3L);
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(price("up", "Up", "0.59", "0.61"), price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotReenterMarketDuringClosedTradeCooldown() {
        TradeEntity closed = openTrade("up", "Up", "0.50", "2.00000000");
        closed.markClosed(
                new BigDecimal("0.55"),
                new BigDecimal("2.00000000"),
                new BigDecimal("1.10"),
                BigDecimal.ZERO,
                clock.instant().minusSeconds(30)
        );
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                any(),
                any(),
                any()
        )).thenReturn(Optional.of(closed));
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(price("up", "Up", "0.59", "0.61"), price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotReenterSameOutcomeAfterLossInMarket() {
        TradeEntity losingUp = openTrade("up", "Up", "0.60", "1.66666700");
        losingUp.markClosed(
                new BigDecimal("0.55"),
                new BigDecimal("1.66666700"),
                new BigDecimal("0.91666685"),
                BigDecimal.ZERO,
                clock.instant().minusSeconds(120)
        );
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(Optional.of(losingUp));
        seedMomentum();
        clock.advance(Duration.ofSeconds(11));
        setOutcomePrices(price("up", "Up", "0.59", "0.61"), price("down", "Down", "0.39", "0.41"));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void activatesTrailingStopInsteadOfSellingImmediatelyOnTakeProfit() {
        TradeEntity open = openTrade("up", "Up", "0.50", "2.00000000");
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.56", "0.58");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotActivateTrailingStopWhenPriceMoveIsNotEnoughAfterFees() {
        TradeEntity open = openTradeWithEntryFee(
                "up",
                "Up",
                "0.66",
                "1.51515152",
                "0.02423564"
        );
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.71", "0.72");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void sellsOnTrailingStopAfterTakeProfitPeakFalls() {
        TradeEntity open = openTrade("up", "Up", "0.50", "2.00000000");
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(price("up", "Up", "0.58", "0.60")));
        strategy.tick();

        OutcomePrice trailingStop = price("up", "Up", "0.54", "0.56");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(trailingStop));

        strategy.tick();

        verify(executionRouter).route(any(TradeIntent.class));
    }

    @Test
    void sellsOnStopLoss() {
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );
        strategy.tick();
        clock.advance(Duration.ofSeconds(11));
        TradeEntity open = openTrade("up", "Up", "0.50", "2.00000000", 12);
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter).route(any(TradeIntent.class));
    }

    @Test
    void doesNotStopLossBeforeMinimumHold() {
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );
        strategy.tick();
        clock.advance(Duration.ofSeconds(4));
        TradeEntity open = openTrade("up", "Up", "0.50", "2.00000000", 4);
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void doesNotStopLossWithoutActualMomentumReversal() {
        TradeEntity open = openTrade("up", "Up", "0.50", "2.00000000", 12);
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void holdsNearExpiryIfSellingWouldLockLoss() {
        market = marketEndingIn(10);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        TradeEntity open = openTrade("up", "Up", "0.50", "2.00000000");
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.45", "0.47");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    @Test
    void holdsNearExpiryWhenSmallPriceMoveIsEatenByCryptoTakerFees() {
        market = marketEndingIn(10);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        TradeEntity open = openTradeWithEntryFee(
                "up",
                "Up",
                "0.66",
                "1.51515152",
                "0.02423564"
        );
        when(tradeRepository.findByStrategyIdAndStatus(CostAwareMomentumStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        OutcomePrice up = price("up", "Up", "0.69", "0.70");
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(up));

        strategy.tick();

        verify(executionRouter, never()).route(any());
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

    private TradeEntity openTrade(String tokenId, String outcome, String entryPrice, String paperShares) {
        return openTrade(tokenId, outcome, entryPrice, paperShares, 5);
    }

    private TradeEntity openTradeWithEntryFee(
            String tokenId,
            String outcome,
            String entryPrice,
            String paperShares,
            String entryFee
    ) {
        TradeEntity trade = openTrade(tokenId, outcome, entryPrice, paperShares);
        trade.markOpen(
                new BigDecimal(entryPrice),
                new BigDecimal(paperShares),
                new BigDecimal("1.00"),
                new BigDecimal(entryFee),
                clock.instant().minusSeconds(5)
        );
        return trade;
    }

    private TradeEntity openTrade(
            String tokenId,
            String outcome,
            String entryPrice,
            String paperShares,
            long secondsAgo
    ) {
        TradeEntity trade = TradeEntity.fromIntent(new TradeIntent(
                CostAwareMomentumStrategy.ID,
                "cost-aware-momentum",
                market.id(),
                market.slug(),
                market.question(),
                null,
                tokenId,
                outcome,
                com.vokerg.voktrader.trade.TradeSide.BUY,
                new BigDecimal("1.00"),
                null,
                com.vokerg.voktrader.trade.TradeOrderType.FOK,
                new BigDecimal(entryPrice),
                new BigDecimal(entryPrice).subtract(new BigDecimal("0.01")),
                new BigDecimal(entryPrice),
                new BigDecimal("0.01"),
                new BigDecimal(entryPrice).subtract(new BigDecimal("0.005")),
                clock.instant().minusMillis(250),
                250L,
                clock.instant().minusSeconds(secondsAgo),
                market.endDate(),
                60L,
                "test"
        ), ExecutionMode.PAPER);
        trade.markOpen(
                new BigDecimal(entryPrice),
                new BigDecimal(paperShares),
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                clock.instant().minusSeconds(secondsAgo)
        );
        return trade;
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
