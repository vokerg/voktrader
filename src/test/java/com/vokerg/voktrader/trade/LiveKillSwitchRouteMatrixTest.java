package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.marketdata.TickMath;
import com.vokerg.voktrader.marketdata.TickRounding;
import com.vokerg.voktrader.marketdata.TickSizeService;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.strategy.v2.StrategyV2FeatureContext;
import com.vokerg.voktrader.strategy.v2.StrategyV2OrderActionBuilder;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.paper.PaperExecutionService;
import com.vokerg.voktrader.trade.paper.PaperOrderGateway;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveKillSwitchRouteMatrixTest {
    private final TradingProperties tradingProperties = new TradingProperties();
    private final ExecutorProperties executorProperties = new ExecutorProperties();
    private final PythonExecutorClient executor = mock(PythonExecutorClient.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeRiskCheckRepository riskCheckRepository = mock(TradeRiskCheckRepository.class);
    private final LiveArmService liveArmService = mock(LiveArmService.class);
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final ExecutionRouter routerTripwire = mock(ExecutionRouter.class);
    private final OrderGateway orderGatewayTripwire = mock(OrderGateway.class);

    private RiskCheckService riskCheckService;
    private StrategyIntentBoundary boundary;

    @BeforeEach
    void setUp() {
        tradingProperties.setMode(ExecutionMode.LIVE);
        tradingProperties.setLiveEnabled(true);
        tradingProperties.setKillSwitchEnabled(true);
        tradingProperties.setExpectedAccountId("0xexpected");
        tradingProperties.setAllowedStrategyIds(Set.of("route-matrix", "strategy-v2-test", "legacy-test"));
        tradingProperties.setMaxOrderUsd(new BigDecimal("5.00"));
        tradingProperties.setMaxSpread(new BigDecimal("0.03"));
        tradingProperties.setMaxPriceAgeMs(1500);
        tradingProperties.setMinSecondsToExpiry(30);
        tradingProperties.setMaxOpenLiveTrades(3);
        tradingProperties.setMaxActivePositionsPerMarket(5);

        executorProperties.setEnabled(true);
        executorProperties.setDryRun(false);
        executorProperties.setApiToken("non-default-token");

        when(liveArmService.status()).thenReturn(new LiveArmService.LiveArmStatus(
                false, null, null, null, false, true, true, false,
                List.of("kill switch is enabled"),
                List.of("kill switch is enabled", "live arm is not active")
        ));
        when(tradeRepository.findPortfolioExposure(isNull(), eq(ExecutionMode.LIVE), anyCollection()))
                .thenReturn(List.of());
        when(tradeRepository.countLiveCapacityTrades(
                eq(List.of(ExecutionMode.LIVE)), anyCollection(), any(Instant.class))).thenReturn(0L);
        when(tradeOrderRepository.findByClientOrderId(anyString())).thenReturn(Optional.empty());

        riskCheckService = new RiskCheckService(
                tradingProperties, tradeRepository, tradeOrderRepository, liveArmService);

        when(routerTripwire.route(any())).thenAnswer(invocation -> {
            executor.submit(mock(ExecutorOrderCommand.class));
            return TradeExecutionResult.accepted(
                    ExecutionMode.LIVE, 1L, 2L, TradeStatus.OPEN, TradeOrderStatus.FILLED, "tripwire");
        });
        when(orderGatewayTripwire.submitOrder(any(), any(), eq(ExecutionMode.LIVE))).thenAnswer(invocation -> {
            executor.submit(mock(ExecutorOrderCommand.class));
            return new OrderLifecycleResult(
                    true, 1L, 2L, "local", "remote", TradeStatus.OPEN,
                    TradeOrderStatus.FILLED, "tripwire", null);
        });

        boundary = new StrategyIntentBoundary(
                executionProperties,
                routerTripwire,
                orderGatewayTripwire,
                tradingProperties,
                riskCheckService,
                riskCheckRepository
        );
    }

    @Test
    void killSwitchBlocksEveryCurrentLiveEntryRouteBeforeExecutorSubmit() {
        assertBlocked("central compatibility route", () -> {
            executionProperties.setUseOrderLayer(false);
            return boundary.accept(entry("route-matrix")).accepted();
        });

        assertBlocked("central order-layer route", () -> {
            executionProperties.setUseOrderLayer(true);
            return boundary.accept(entry("route-matrix")).accepted();
        });

        assertBlocked("legacy strategy adapter", () -> {
            executionProperties.setUseOrderLayer(false);
            LegacyStrategyIntentAdapter adapter = new LegacyStrategyIntentAdapter(boundary, boundary);
            return adapter.routeEntry(entry("legacy-test")).accepted();
        });

        assertBlocked("Strategy V2 order action", () -> strategyV2Entry().accepted());
        assertBlocked("raw compatibility router", () -> rawExecutionRouter().route(entry("route-matrix").tradeIntent()).accepted());
        assertBlocked("raw primary order gateway", () -> rawRoutingOrderGateway()
                .submitOrder(entry("route-matrix").tradeIntent(), StrategyInstanceKey.of(null, "route-matrix"), ExecutionMode.LIVE)
                .success());
        assertBlocked("raw live order gateway", () -> rawLiveOrderGateway()
                .submitOrder(entry("route-matrix").tradeIntent(), StrategyInstanceKey.of(null, "route-matrix"), ExecutionMode.LIVE)
                .success());
        assertBlocked("direct compatibility live service", () -> rawLiveExecutionService()
                .execute(entry("route-matrix").tradeIntent(), ExecutionMode.LIVE)
                .accepted());
        assertBlocked("direct order manager", () -> rawOrderManager()
                .submitOrder(entry("route-matrix").tradeIntent(), ExecutionMode.LIVE)
                .success());
    }

    @Test
    void mutationEquivalentBypassControlProvesHarnessDetectsExecutorSubmit() {
        assertThatThrownBy(() -> assertBlocked("mutation control", () -> {
            executor.submit(mock(ExecutorOrderCommand.class));
            return false;
        })).isInstanceOf(AssertionError.class);
    }

    @Test
    void sellAndCancelRemainAvailableWithKillSwitchEnabled() {
        executionProperties.setUseOrderLayer(false);
        when(routerTripwire.route(any())).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.LIVE, 1L, 2L, TradeStatus.CLOSED, TradeOrderStatus.FILLED, "exit accepted"));

        TradeExecutionResult exit = boundary.submit(exit());

        assertThat(exit.accepted()).isTrue();
        verify(routerTripwire).route(any());

        OrderManager orderManager = mock(OrderManager.class);
        when(orderManager.cancelOrder("local-order", "operator cancel")).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "local-order", "remote-order", TradeStatus.EXIT_PENDING,
                TradeOrderStatus.CANCEL_REQUESTED, "cancel accepted", null));
        LiveOrderGateway gateway = new LiveOrderGateway(orderManager);

        OrderLifecycleResult cancellation = gateway.cancelOrder("local-order", "operator cancel");

        assertThat(cancellation.success()).isTrue();
        verify(orderManager).cancelOrder("local-order", "operator cancel");
    }

    private void assertBlocked(String route, BooleanSupplier attempt) {
        clearInvocations(executor);
        assertThat(attempt.getAsBoolean()).as(route + " must be rejected").isFalse();
        verify(executor, never()).submit(any(ExecutorOrderCommand.class));
    }

    private ExecutionRouter rawExecutionRouter() {
        PaperExecutionService paper = mock(PaperExecutionService.class);
        LiveExecutionService live = mock(LiveExecutionService.class);
        when(live.execute(any(), eq(ExecutionMode.LIVE))).thenAnswer(invocation -> {
            executor.submit(mock(ExecutorOrderCommand.class));
            return TradeExecutionResult.accepted(
                    ExecutionMode.LIVE, 1L, 2L, TradeStatus.OPEN, TradeOrderStatus.FILLED, "tripwire");
        });
        return new ExecutionRouter(tradingProperties, paper, live);
    }

    private RoutingOrderGateway rawRoutingOrderGateway() {
        LiveOrderGateway live = mock(LiveOrderGateway.class);
        PaperOrderGateway paper = mock(PaperOrderGateway.class);
        when(live.submitOrder(any(), any(), eq(ExecutionMode.LIVE))).thenAnswer(invocation -> {
            executor.submit(mock(ExecutorOrderCommand.class));
            return new OrderLifecycleResult(
                    true, 1L, 2L, "local", "remote", TradeStatus.OPEN,
                    TradeOrderStatus.FILLED, "tripwire", null);
        });
        return new RoutingOrderGateway(tradingProperties, executionProperties, live, paper);
    }

    private LiveOrderGateway rawLiveOrderGateway() {
        OrderManager orderManager = mock(OrderManager.class);
        when(orderManager.submitOrder(any(), eq(ExecutionMode.LIVE))).thenAnswer(invocation -> {
            executor.submit(mock(ExecutorOrderCommand.class));
            return new OrderLifecycleResult(
                    true, 1L, 2L, "local", "remote", TradeStatus.OPEN,
                    TradeOrderStatus.FILLED, "tripwire", null);
        });
        return new LiveOrderGateway(orderManager);
    }

    private LiveExecutionService rawLiveExecutionService() {
        return new LiveExecutionService(
                riskCheckService,
                riskCheckRepository,
                tradeRepository,
                tradeOrderRepository,
                mock(TradeFillRepository.class),
                mock(TradeEventRepository.class),
                executor,
                executorProperties,
                tradingProperties,
                mock(PolymarketFeeCalculator.class),
                mock(TradingEventLogger.class),
                mock(ObjectMapper.class)
        );
    }

    private OrderManager rawOrderManager() {
        LiveArmService actualArm = new LiveArmService(tradingProperties, executorProperties);
        return new OrderManager(
                tradeRepository,
                tradeOrderRepository,
                mock(TradeFillRepository.class),
                executor,
                executorProperties,
                actualArm,
                mock(OrderReconciliationService.class),
                mock(OrderCancellationEventEmitter.class),
                mock(ObjectMapper.class)
        );
    }

    private TradeExecutionResult strategyV2Entry() {
        executionProperties.setUseOrderLayer(false);
        TickSizeService tickSizeService = mock(TickSizeService.class);
        when(tickSizeService.requireTickSize(anyString())).thenReturn(new BigDecimal("0.01"));
        when(tickSizeService.round(anyString(), any(BigDecimal.class), any(TickRounding.class)))
                .thenAnswer(invocation -> TickMath.round(
                        invocation.getArgument(1), new BigDecimal("0.01"), invocation.getArgument(2)));
        StrategyV2OrderActionBuilder builder = new StrategyV2OrderActionBuilder(
                boundary, boundary, tradingProperties, tickSizeService);
        return builder.routeEntry(strategyV2(), strategyV2Context());
    }

    private StrategyV2Properties.Strategy strategyV2() {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        strategy.setStrategyId("strategy-v2-test");
        StrategyV2Properties.Entry entry = new StrategyV2Properties.Entry();
        entry.setRuleId("entry");
        StrategyV2Properties.Action action = new StrategyV2Properties.Action();
        action.setOrderType(TradeOrderType.FOK.name());
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setUsd(new BigDecimal("1.00"));
        action.setSize(size);
        entry.setAction(action);
        strategy.setEntry(entry);
        return strategy;
    }

    private StrategyV2FeatureContext strategyV2Context() {
        StrategyOutcomeView candidate = mock(StrategyOutcomeView.class);
        when(candidate.tokenId()).thenReturn("token-id");
        when(candidate.outcome()).thenReturn("Up");
        when(candidate.spread()).thenReturn(new BigDecimal("0.02"));
        Map<String, Object> features = new HashMap<>();
        features.put("candidate.bid", new BigDecimal("0.49"));
        features.put("candidate.ask", new BigDecimal("0.51"));
        return new StrategyV2FeatureContext(
                market(), null, candidate, null, Instant.now(), features);
    }

    private EntryIntent entry(String strategyId) {
        return EntryIntent.buy(
                market(), price(), new BigDecimal("1.00"), strategyId, "entry", "route proof");
    }

    private ExitIntent exit() {
        return ExitIntent.sell(
                market(), price(), new BigDecimal("1.00"), "route-matrix", "exit", "risk reduction");
    }

    private OutcomePrice price() {
        return new OutcomePrice(
                "token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"),
                new BigDecimal("0.02"), Instant.now().minusMillis(100));
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id", "Question", "condition-id", "slug",
                Instant.now().plusSeconds(900), true, false, true, false,
                null, null, null, null);
    }
}
