package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.FeeEstimate;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookSide;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolutionPressureFokStrategyTest {
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
    private ResolutionPressureFokStrategy strategy;

    @BeforeEach
    void setUp() {
        market = marketEndingIn(60);
        StrategyTradeSupport tradeSupport = new StrategyTradeSupport(tradeRepository);
        StrategyProperties properties = new StrategyProperties(ResolutionPressureFokStrategy.ID, 1000L, null, null, null, null, null, null, null);
        strategy = new ResolutionPressureFokStrategy(
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
                marketDataProvider,
                properties,
                clock
        );
        when(strategyTimeWindow.isInsideTradingWindow()).thenReturn(true);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        when(tradeRepository.findByStrategyIdAndStatus(ResolutionPressureFokStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(ResolutionPressureFokStrategy.ID, market.id(), TradeStatus.OPEN))
                .thenReturn(Optional.empty());
        when(tradeRepository.countByStrategyIdAndMarketId(ResolutionPressureFokStrategy.ID, market.id())).thenReturn(0L);
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
    void routesFokAtEstimatedTakerAverageWhenLatePressureAndDepthPass() {
        OutcomePrice upStart = price("up", "Up", "0.54", "0.56");
        OutcomePrice downStart = price("down", "Down", "0.43", "0.45");
        publish(upStart, downStart, view(upStart, true), view(downStart, false));

        strategy.tick();

        verify(executionRouter, never()).route(any());

        clock.advance(Duration.ofSeconds(6));
        market = marketEndingIn(54);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        OutcomePrice upNow = price("up", "Up", "0.56", "0.58");
        OutcomePrice downNow = price("down", "Down", "0.41", "0.43");
        publish(upNow, downNow, view(upNow, true), view(downNow, false));

        strategy.tick();

        ArgumentCaptor<TradeIntent> intent = ArgumentCaptor.forClass(TradeIntent.class);
        verify(executionRouter).route(intent.capture());
        assertThat(intent.getValue().strategyId()).isEqualTo(ResolutionPressureFokStrategy.ID);
        assertThat(intent.getValue().ruleId()).isEqualTo(ResolutionPressureFokStrategy.ID);
        assertThat(intent.getValue().orderType()).isEqualTo(TradeOrderType.FOK);
        assertThat(intent.getValue().tokenId()).isEqualTo("up");
        assertThat(intent.getValue().limitPrice()).isEqualByComparingTo("0.58500000");
    }

    private void publish(
            OutcomePrice up,
            OutcomePrice down,
            StrategyOutcomeView upView,
            StrategyOutcomeView downView
    ) {
        when(latestPriceState.byOutcome("Up")).thenReturn(Optional.of(up));
        when(latestPriceState.byOutcome("Down")).thenReturn(Optional.of(down));
        StrategyMarketView marketView = mock(StrategyMarketView.class);
        when(marketView.market()).thenReturn(market);
        when(marketView.now()).thenReturn(clock.instant());
        when(marketView.up()).thenReturn(upView);
        when(marketView.down()).thenReturn(downView);
        when(marketView.outcomes()).thenReturn(List.of(upView, downView));
        when(marketView.token("up")).thenReturn(Optional.of(upView));
        when(marketView.token("down")).thenReturn(Optional.of(downView));
        when(marketView.secondsToExpiry()).thenReturn(Optional.of(Duration.between(clock.instant(), market.endDate()).toSeconds()));
        when(marketDataProvider.currentUpDownMarket()).thenReturn(Optional.of(marketView));
    }

    private StrategyOutcomeView view(OutcomePrice price, boolean liquid) {
        StrategyOutcomeView view = mock(StrategyOutcomeView.class);
        when(view.outcome()).thenReturn(price.outcome());
        when(view.tokenId()).thenReturn(price.tokenId());
        when(view.price()).thenReturn(price);
        when(view.mid()).thenReturn(price.bid().add(price.ask()).divide(new BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP));
        when(view.spread()).thenReturn(price.spread());
        when(view.orderBook()).thenReturn(Optional.of(mock(OutcomeOrderBook.class)));
        when(view.bookAgeMs()).thenReturn(Optional.of(100L));
        when(view.askDepthWithin(any())).thenReturn(new BigDecimal(liquid ? "5.0" : "1.0"));
        FillEstimate fill = new FillEstimate(
                price.tokenId(),
                price.outcome(),
                OrderBookSide.BUY,
                new BigDecimal("1.00"),
                null,
                new BigDecimal("1.70940171"),
                new BigDecimal("1.00"),
                new BigDecimal("0.58500000"),
                new BigDecimal("0.59"),
                liquid,
                2,
                clock.instant()
        );
        when(view.estimateTakerBuy(any())).thenReturn(Optional.of(fill));
        when(view.estimateTakerFee(fill)).thenReturn(Optional.of(new FeeEstimate(
                LiquidityRole.TAKER,
                new BigDecimal("0.072"),
                new BigDecimal("0.02500000")
        )));
        return view;
    }

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(tokenId, outcome, bidValue, askValue, askValue.subtract(bidValue), clock.instant());
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
