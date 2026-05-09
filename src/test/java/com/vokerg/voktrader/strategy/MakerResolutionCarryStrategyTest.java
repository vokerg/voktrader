package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.ExitEconomy;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderStatus;
import com.vokerg.voktrader.trade.TradeOrderType;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MakerResolutionCarryStrategyTest {
    private final LatestPriceState latestPriceState = mock(LatestPriceState.class);
    private final TrackedMarketState trackedMarketState = mock(TrackedMarketState.class);
    private final StrategyTimeWindow strategyTimeWindow = mock(StrategyTimeWindow.class);
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeEconomy tradeEconomy = mock(TradeEconomy.class);
    private final TradingEventLogger eventLogger = mock(TradingEventLogger.class);
    private final StrategyMarketDataProvider marketDataProvider = mock(StrategyMarketDataProvider.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-04-30T10:00:00Z"));
    private GammaMarketDto market;
    private MakerResolutionCarryStrategy strategy;

    @BeforeEach
    void setUp() {
        market = marketEndingIn(240);
        StrategyTradeSupport tradeSupport = new StrategyTradeSupport(tradeRepository);
        StrategyProperties properties = new StrategyProperties(MakerResolutionCarryStrategy.ID, 1000L, null, null, null, null, null, null, null);
        strategy = new MakerResolutionCarryStrategy(
                new StrategyEntrySupport(
                        latestPriceState,
                        trackedMarketState,
                        strategyTimeWindow,
                        tradeSupport,
                        executionRouter,
                        eventLogger,
                        marketDataProvider,
                        clock
                ),
                new StrategyExitSupport(trackedMarketState, latestPriceState, tradeSupport, executionRouter, tradeEconomy, eventLogger),
                tradeSupport,
                properties,
                clock
        );
        when(strategyTimeWindow.isInsideTradingWindow()).thenReturn(true);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        when(tradeRepository.findByStrategyIdAndStatus(MakerResolutionCarryStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(MakerResolutionCarryStrategy.ID, market.id(), TradeStatus.OPEN))
                .thenReturn(Optional.empty());
        when(tradeRepository.countByStrategyIdAndMarketId(MakerResolutionCarryStrategy.ID, market.id())).thenReturn(0L);
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(executionRouter.route(any(TradeIntent.class))).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER,
                1L,
                1L,
                TradeStatus.OPEN,
                TradeOrderStatus.FILLED,
                "accepted"
        ));
    }

    @Test
    void makerEntryRoutesGtcAtBid() {
        OutcomePrice upStart = price("up", "Up", "0.48", "0.50");
        OutcomePrice downStart = price("down", "Down", "0.50", "0.52");
        publish(upStart, downStart, view(upStart, true), view(downStart, false));
        strategy.tick();

        clock.advance(Duration.ofSeconds(6));
        OutcomePrice upNow = price("up", "Up", "0.51", "0.53");
        OutcomePrice downNow = price("down", "Down", "0.47", "0.49");
        publish(upNow, downNow, view(upNow, true), view(downNow, false));
        strategy.tick();

        ArgumentCaptor<TradeIntent> intent = ArgumentCaptor.forClass(TradeIntent.class);
        verify(executionRouter).route(intent.capture());
        assertThat(intent.getValue().orderType()).isEqualTo(TradeOrderType.GTC);
        assertThat(intent.getValue().limitPrice()).isEqualByComparingTo("0.51");
    }

    @Test
    void nearExpiryPoorExitExplicitlyWaitsForResolution() {
        market = marketEndingIn(10);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        TradeEntity open = openTrade("up", "Up", "0.51", "1.96078431");
        when(tradeRepository.findByStrategyIdAndStatus(MakerResolutionCarryStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of(open));
        when(latestPriceState.byTokenId("up")).thenReturn(Optional.of(price("up", "Up", "0.30", "0.32")));
        when(tradeEconomy.estimateExit(any(TradeEntity.class), any(BigDecimal.class), any(BigDecimal.class), any()))
                .thenReturn(new ExitEconomy(
                        LiquidityRole.TAKER,
                        new BigDecimal("0.58823529"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("-0.41176471"),
                        new BigDecimal("0.08"),
                        false
                ));

        strategy.tick();

        verify(executionRouter, never()).route(any());
        verify(eventLogger).execution(
                eq("EXIT_WAIT_FOR_RESOLUTION"),
                eq("EXIT"),
                eq(MakerResolutionCarryStrategy.ID),
                eq("maker-resolution-carry"),
                any(),
                eq(market.id()),
                eq("up"),
                eq("Up"),
                any(),
                any(),
                eq(true)
        );
    }

    private void publish(OutcomePrice up, OutcomePrice down, StrategyOutcomeView upView, StrategyOutcomeView downView) {
        when(latestPriceState.byOutcome("Up")).thenReturn(Optional.of(up));
        when(latestPriceState.byOutcome("Down")).thenReturn(Optional.of(down));
        StrategyMarketView marketView = mock(StrategyMarketView.class);
        when(marketView.up()).thenReturn(upView);
        when(marketView.down()).thenReturn(downView);
        when(marketDataProvider.currentUpDownMarket()).thenReturn(Optional.of(marketView));
    }

    private StrategyOutcomeView view(OutcomePrice price, boolean liquid) {
        StrategyOutcomeView view = mock(StrategyOutcomeView.class);
        when(view.price()).thenReturn(price);
        when(view.tokenId()).thenReturn(price.tokenId());
        when(view.outcome()).thenReturn(price.outcome());
        when(view.mid()).thenReturn(price.bid().add(price.ask()).divide(new BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP));
        when(view.spread()).thenReturn(price.spread());
        when(view.orderBook()).thenReturn(Optional.of(mock(OutcomeOrderBook.class)));
        when(view.bookAgeMs()).thenReturn(Optional.of(100L));
        when(view.bidDepthWithin(any())).thenReturn(new BigDecimal(liquid ? "5.0" : "1.0"));
        return view;
    }

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(tokenId, outcome, bidValue, askValue, askValue.subtract(bidValue), clock.instant());
    }

    private TradeEntity openTrade(String tokenId, String outcome, String entryPrice, String shares) {
        TradeEntity trade = TradeEntity.fromIntent(TradeIntent.buyMaker(
                null,
                market,
                price(tokenId, outcome, entryPrice, new BigDecimal(entryPrice).add(new BigDecimal("0.02")).toPlainString()),
                new BigDecimal("1.00"),
                MakerResolutionCarryStrategy.ID,
                "maker-resolution-carry",
                "test"
        ), ExecutionMode.PAPER);
        trade.markOpen(new BigDecimal(entryPrice), new BigDecimal(shares), new BigDecimal("1.00"), BigDecimal.ZERO, clock.instant().minusSeconds(5));
        return trade;
    }

    private GammaMarketDto marketEndingIn(long seconds) {
        return new GammaMarketDto("market-id", "BTC Up or Down?", "condition-id", "btc-updown", clock.instant().plusSeconds(seconds), true, false, true, false, null, null, null, null);
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
