package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketDataProvider;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeOrderPhase;
import com.vokerg.voktrader.trade.TradeOrderStatus;
import com.vokerg.voktrader.trade.TradeOrderType;
import com.vokerg.voktrader.trade.TradeStateProvider;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.TradingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyV2RuntimeStateAwarenessTest {
    private final StrategyV2Properties properties = new StrategyV2Properties();
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final StrategyMarketDataProvider marketDataProvider = mock(StrategyMarketDataProvider.class);
    private final TrackedMarketState trackedMarketState = mock(TrackedMarketState.class);
    private final StrategyV2FeatureResolver featureResolver = mock(StrategyV2FeatureResolver.class);
    private final StrategyV2EntryEvaluator entryEvaluator = mock(StrategyV2EntryEvaluator.class);
    private final StrategyV2ExitEvaluator exitEvaluator = mock(StrategyV2ExitEvaluator.class);
    private final StrategyV2DiagnosticsRecorder diagnosticsRecorder = mock(StrategyV2DiagnosticsRecorder.class);
    private final TradeStateProvider tradeStateProvider = mock(TradeStateProvider.class);
    private final OrderGateway orderGateway = mock(OrderGateway.class);
    private final TradingProperties tradingProperties = new TradingProperties();

    @Test
    void flagDisabledUsesOldEntryFlowWithoutStateLookup() {
        EngineFixture fixture = fixture(false, StrategyRuntimeState.empty(StrategyInstanceKey.of(null, "strategy-test"), "market-id"));

        fixture.engine.tick();

        verify(tradeStateProvider, never()).getState(any(), any());
        verify(featureResolver).contexts(any(), any(), any());
        verify(entryEvaluator).evaluate(eq(fixture.strategy), anyList(), eq(featureResolver), eq(ExecutionMode.PAPER));
    }

    @Test
    void noActiveTradeEvaluatesNormalEntryWhenOrderLayerEnabled() {
        EngineFixture fixture = fixture(true, StrategyRuntimeState.empty(StrategyInstanceKey.of(null, "strategy-test"), "market-id"));

        fixture.engine.tick();

        verify(tradeStateProvider).getState(any(StrategyInstanceKey.class), eq("market-id"));
        verify(entryEvaluator).evaluate(eq(fixture.strategy), anyList(), eq(featureResolver), eq(ExecutionMode.PAPER));
    }

    @Test
    void entryPendingSuppressesDuplicateEntry() {
        EngineFixture fixture = fixture(true, state(TradeStatus.ENTRY_PENDING, entryOrder(TradeOrderStatus.SUBMITTED, 1), null));

        fixture.engine.tick();

        verify(entryEvaluator, never()).evaluate(any(), anyList(), any(), any());
        verify(orderGateway, never()).cancelOrder(any(), any());
    }

    @Test
    void entryPendingTooOldRequestsCancel() {
        EngineFixture fixture = fixture(true, state(TradeStatus.ENTRY_PENDING, entryOrder(TradeOrderStatus.RESTING, 30), null));
        fixture.strategy.getEntryOrderManagement().setMaxPendingSeconds(1);
        when(orderGateway.cancelOrder(eq("entry-local"), any())).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "entry-local", "entry-remote", TradeStatus.ENTRY_PENDING, TradeOrderStatus.CANCEL_REQUESTED, "cancel accepted", null
        ));

        fixture.engine.tick();

        verify(entryEvaluator, never()).evaluate(any(), anyList(), any(), any());
        verify(orderGateway).cancelOrder(eq("entry-local"), any());
    }

    @Test
    void openPositionEvaluatesExitRulesOnly() {
        StrategyRuntimeState state = state(TradeStatus.OPEN, null, null);
        EngineFixture fixture = fixture(true, state);

        fixture.engine.tick();

        verify(exitEvaluator).evaluate(eq(fixture.strategy), any(), any(), eq(state), eq(ExecutionMode.PAPER));
        verify(entryEvaluator, never()).evaluate(any(), anyList(), any(), any());
    }

    @Test
    void exitPendingSuppressesDuplicateExitAndEntry() {
        EngineFixture fixture = fixture(true, state(TradeStatus.EXIT_PENDING, null, exitOrder(TradeOrderStatus.SUBMITTED, 1)));

        fixture.engine.tick();

        verify(exitEvaluator, never()).evaluate(any(), any(), any(), any(StrategyRuntimeState.class), any());
        verify(entryEvaluator, never()).evaluate(any(), anyList(), any(), any());
        verify(orderGateway, never()).cancelOrder(any(), any());
    }

    @Test
    void partiallyOpenExposesPartialStateWithoutNormalEntry() {
        StrategyRuntimeState state = state(TradeStatus.PARTIALLY_OPEN, entryOrder(TradeOrderStatus.PARTIALLY_FILLED, 1), null);
        EngineFixture fixture = fixture(true, state);

        fixture.engine.tick();

        verify(exitEvaluator).evaluate(eq(fixture.strategy), any(), any(), eq(state), eq(ExecutionMode.PAPER));
        verify(entryEvaluator, never()).evaluate(any(), anyList(), any(), any());
    }

    private EngineFixture fixture(boolean useOrderLayer, StrategyRuntimeState state) {
        properties.getEngine().setEnabled(true);
        StrategyV2Properties.Strategy strategy = strategy();
        properties.setStrategies(List.of(strategy));
        executionProperties.setUseOrderLayer(useOrderLayer);
        tradingProperties.setMode(ExecutionMode.PAPER);
        StrategyV2Registry registry = new StrategyV2Registry(properties);
        StrategyMarketView marketView = marketView();
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market()));
        when(marketDataProvider.currentUpDownMarket()).thenReturn(Optional.of(marketView));
        when(tradeStateProvider.getState(any(StrategyInstanceKey.class), eq("market-id"))).thenReturn(state);
        List<StrategyV2FeatureContext> contexts = List.of(context());
        when(featureResolver.contexts(any(), any(), any())).thenReturn(contexts);
        when(featureResolver.contexts(any(), any(), any(), any())).thenReturn(contexts);
        when(entryEvaluator.evaluate(any(), anyList(), eq(featureResolver), any())).thenReturn(Optional.empty());
        StrategyV2Engine engine = new StrategyV2Engine(
                properties,
                registry,
                marketDataProvider,
                trackedMarketState,
                featureResolver,
                entryEvaluator,
                exitEvaluator,
                diagnosticsRecorder,
                executionProperties,
                tradeStateProvider,
                orderGateway,
                tradingProperties
        );
        return new EngineFixture(engine, strategy);
    }

    private StrategyV2Properties.Strategy strategy() {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        strategy.setStrategyId("strategy-test");
        StrategyV2Properties.Entry entry = new StrategyV2Properties.Entry();
        StrategyV2Properties.Action action = new StrategyV2Properties.Action();
        action.setOrderType(TradeOrderType.FOK.name());
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setPaperUsd(new BigDecimal("1.00"));
        action.setSize(size);
        entry.setAction(action);
        strategy.setEntry(entry);
        return strategy;
    }

    private StrategyRuntimeState state(TradeStatus status, OrderRuntimeState entryOrder, OrderRuntimeState exitOrder) {
        return new StrategyRuntimeState(
                StrategyInstanceKey.of(null, "strategy-test"),
                "market-id",
                "strategy-test",
                "token-up",
                status,
                entryOrder,
                exitOrder,
                new BigDecimal("5"),
                entryOrder == null ? null : entryOrder.remainingShares(),
                new BigDecimal("0.50"),
                null,
                false,
                null,
                null,
                null,
                null,
                TimeMachine.now(),
                null
        );
    }

    private OrderRuntimeState entryOrder(TradeOrderStatus status, long ageSeconds) {
        return order(TradeOrderPhase.ENTRY, status, "entry-local", "entry-remote", ageSeconds);
    }

    private OrderRuntimeState exitOrder(TradeOrderStatus status, long ageSeconds) {
        return order(TradeOrderPhase.EXIT, status, "exit-local", "exit-remote", ageSeconds);
    }

    private OrderRuntimeState order(TradeOrderPhase phase, TradeOrderStatus status, String localId, String remoteId, long ageSeconds) {
        Instant submittedAt = TimeMachine.now().minusSeconds(ageSeconds);
        return new OrderRuntimeState(
                2L,
                localId,
                remoteId,
                phase,
                status,
                new BigDecimal("0.50"),
                new BigDecimal("10"),
                BigDecimal.ZERO,
                new BigDecimal("10"),
                null,
                null,
                false,
                null,
                submittedAt,
                submittedAt,
                null,
                submittedAt,
                null
        );
    }

    private StrategyV2FeatureContext context() {
        return new StrategyV2FeatureContext(market(), marketView(), outcome("Up", "token-up"), outcome("Down", "token-down"), TimeMachine.now(), Map.of());
    }

    private StrategyMarketView marketView() {
        StrategyMarketView marketView = mock(StrategyMarketView.class);
        StrategyOutcomeView up = outcome("Up", "token-up");
        StrategyOutcomeView down = outcome("Down", "token-down");
        when(marketView.outcome("Up")).thenReturn(Optional.of(up));
        when(marketView.outcome("Down")).thenReturn(Optional.of(down));
        when(marketView.outcomes()).thenReturn(List.of(up, down));
        return marketView;
    }

    private StrategyOutcomeView outcome(String name, String tokenId) {
        StrategyOutcomeView view = mock(StrategyOutcomeView.class);
        when(view.outcome()).thenReturn(name);
        when(view.tokenId()).thenReturn(tokenId);
        when(view.mid()).thenReturn(new BigDecimal("0.50"));
        when(view.spread()).thenReturn(BigDecimal.ZERO);
        return view;
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "Question",
                "condition-id",
                "slug",
                TimeMachine.now().plusSeconds(60),
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

    private record EngineFixture(StrategyV2Engine engine, StrategyV2Properties.Strategy strategy) {
    }
}
