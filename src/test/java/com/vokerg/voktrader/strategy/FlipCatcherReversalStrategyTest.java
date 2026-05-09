package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.ExitEconomy;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderStatus;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlipCatcherReversalStrategyTest {

    private final LatestPriceState latestPriceState = mock(LatestPriceState.class);
    private final TrackedMarketState trackedMarketState = mock(TrackedMarketState.class);
    private final StrategyTimeWindow strategyTimeWindow = mock(StrategyTimeWindow.class);
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeEconomy tradeEconomy = mock(TradeEconomy.class);
    private final TradingEventLogger eventLogger = mock(TradingEventLogger.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-04-30T10:00:00Z"));
    private FlipCatcherReversalStrategy strategy;
    private GammaMarketDto market;

    @BeforeEach
    void setUp() {
        StrategyProperties properties = new StrategyProperties(
                FlipCatcherReversalStrategy.ID,
                1000L,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        StrategyTradeSupport tradeSupport = new StrategyTradeSupport(tradeRepository);
        strategy = new FlipCatcherReversalStrategy(
                new StrategyEntrySupport(
                        latestPriceState,
                        trackedMarketState,
                        strategyTimeWindow,
                        tradeSupport,
                        executionRouter,
                        eventLogger,
                        mock(StrategyMarketDataProvider.class),
                        clock
                ),
                new StrategyExitSupport(trackedMarketState, latestPriceState, tradeSupport, executionRouter, tradeEconomy, eventLogger),
                tradeSupport,
                properties,
                clock
        );
        market = marketEndingIn(240);

        when(strategyTimeWindow.isInsideTradingWindow()).thenReturn(true);
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market));
        when(tradeRepository.findByStrategyIdAndStatus(FlipCatcherReversalStrategy.ID, TradeStatus.OPEN)).thenReturn(List.of());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
                FlipCatcherReversalStrategy.ID,
                market.id(),
                TradeStatus.OPEN
        )).thenReturn(Optional.empty());
        when(tradeRepository.countByStrategyIdAndMarketId(
                FlipCatcherReversalStrategy.ID,
                market.id()
        )).thenReturn(0L);
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(latestPriceState.byTokenId(any())).thenReturn(Optional.empty());
        when(executionRouter.route(any(TradeIntent.class))).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER,
                1L,
                1L,
                TradeStatus.OPEN,
                TradeOrderStatus.FILLED,
                "accepted"
        ));
        when(tradeEconomy.estimateExit(any(TradeEntity.class), any(BigDecimal.class), any(BigDecimal.class), any()))
                .thenAnswer(invocation -> {
                    TradeEntity trade = invocation.getArgument(0);
                    BigDecimal exitPrice = invocation.getArgument(1);
                    BigDecimal minimumProfit = invocation.getArgument(2);
                    return exitEconomy(trade, exitPrice, minimumProfit);
                });
    }

    @Test
    void buysMidrangeSideThatAcceleratesWhileOppositeWeakens() {
        setOutcomePrices(
                price("up", "Up", "0.43", "0.45"),
                price("down", "Down", "0.55", "0.57")
        );
        strategy.tick();

        clock.advance(Duration.ofSeconds(6));
        OutcomePrice upFlip = price("up", "Up", "0.49", "0.51");
        setOutcomePrices(upFlip, price("down", "Down", "0.49", "0.51"));

        strategy.tick();

        ArgumentCaptor<TradeIntent> intent = ArgumentCaptor.forClass(TradeIntent.class);
        verify(executionRouter).route(intent.capture());
        assertThat(intent.getValue().strategyId()).isEqualTo(FlipCatcherReversalStrategy.ID);
        assertThat(intent.getValue().ruleId()).isEqualTo("flip-catcher");
        assertThat(intent.getValue().tokenId()).isEqualTo("up");
        assertThat(intent.getValue().limitPrice()).isEqualByComparingTo(upFlip.ask());
    }

    @Test
    void doesNotBuyAfterFlipAlreadyRepricedAwayFromMidrange() {
        setOutcomePrices(
                price("up", "Up", "0.43", "0.45"),
                price("down", "Down", "0.55", "0.57")
        );
        strategy.tick();

        clock.advance(Duration.ofSeconds(6));
        setOutcomePrices(
                price("up", "Up", "0.59", "0.61"),
                price("down", "Down", "0.39", "0.41")
        );

        strategy.tick();

        verify(executionRouter, never()).route(any());
    }

    private void setOutcomePrices(OutcomePrice up, OutcomePrice down) {
        when(latestPriceState.byOutcome("Up")).thenReturn(Optional.of(up));
        when(latestPriceState.byOutcome("Down")).thenReturn(Optional.of(down));
    }

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(
                tokenId,
                outcome,
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                clock.instant()
        );
    }

    private TradeEntity openTrade(String tokenId, String outcome, String entryPrice, String shares) {
        TradeEntity trade = TradeEntity.fromIntent(new TradeIntent(
                null,
                FlipCatcherReversalStrategy.ID,
                "flip-catcher",
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
                false,
                new BigDecimal(entryPrice),
                new BigDecimal(entryPrice).subtract(new BigDecimal("0.01")),
                new BigDecimal(entryPrice),
                new BigDecimal("0.01"),
                new BigDecimal(entryPrice).subtract(new BigDecimal("0.005")),
                clock.instant().minusMillis(250),
                250L,
                clock.instant().minusSeconds(5),
                market.endDate(),
                60L,
                "test"
        ), ExecutionMode.PAPER);
        trade.markOpen(
                new BigDecimal(entryPrice),
                new BigDecimal(shares),
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                clock.instant().minusSeconds(5)
        );
        return trade;
    }

    private ExitEconomy exitEconomy(TradeEntity trade, BigDecimal exitPrice, BigDecimal minimumProfit) {
        BigDecimal shares = trade.getEntryFilledShares() == null ? BigDecimal.ZERO : trade.getEntryFilledShares();
        BigDecimal entryCost = trade.getEntryFilledUsd() == null ? BigDecimal.ZERO : trade.getEntryFilledUsd();
        BigDecimal netPnl = shares.multiply(exitPrice).subtract(entryCost);
        return new ExitEconomy(
                LiquidityRole.TAKER,
                shares.multiply(exitPrice),
                BigDecimal.ZERO,
                trade.getEntryFeeUsd() == null ? BigDecimal.ZERO : trade.getEntryFeeUsd(),
                netPnl,
                minimumProfit,
                netPnl.compareTo(minimumProfit) >= 0
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
