package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.VoktraderApplication;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketDataProvider;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.support.ExecutorTestConfig;
import com.vokerg.voktrader.support.ScriptedExecutorClient;
import com.vokerg.voktrader.trade.DbTradeStateProvider;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderManager;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = VoktraderApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:strategy-v2-lifecycle-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.task.scheduling.enabled=false",
                "voktrader.bots.enabled=false",
                "voktrader.order-layer.reconciliation.enabled=false",
                "voktrader.trading.mode=LIVE",
                "voktrader.trading.live-enabled=true",
                "voktrader.trading.kill-switch-enabled=false",
                "voktrader.trading.allowed-strategy-ids=it-v2"
        }
)
@Import({
        ExecutorTestConfig.class,
        StrategyV2OrderLifecycleIntegrationTest.MockMarketConfig.class
})
class StrategyV2OrderLifecycleIntegrationTest {
    private static final String STRATEGY_ID = "it-v2";
    private static final String MARKET_ID = "market-v2";
    private static final String TOKEN_ID = "token-down";

    @Autowired
    private StrategyV2Engine engine;

    @Autowired
    private StrategyV2Properties strategyProperties;

    @Autowired
    private StrategyV2ExecutionProperties executionProperties;

    @Autowired
    private TradingProperties tradingProperties;

    @Autowired
    private OrderManager orderManager;

    @Autowired
    private DbTradeStateProvider dbTradeStateProvider;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private TradeOrderRepository tradeOrderRepository;

    @Autowired
    private TradeFillRepository tradeFillRepository;

    @Autowired
    private TradeEventRepository tradeEventRepository;

    @Autowired
    private TradeRiskCheckRepository tradeRiskCheckRepository;

    @Autowired
    private ScriptedExecutorClient executor;

    @Autowired
    private StrategyMarketDataProvider marketDataProvider;

    @Autowired
    private TrackedMarketState trackedMarketState;

    @BeforeEach
    void setUp() {
        tradeEventRepository.deleteAll();
        tradeFillRepository.deleteAll();
        tradeOrderRepository.deleteAll();
        tradeRiskCheckRepository.deleteAll();
        tradeRepository.deleteAll();
        executor.reset();
        reset(marketDataProvider, trackedMarketState);

        tradingProperties.setMode(ExecutionMode.LIVE);
        executionProperties.setUseOrderLayer(true);
        strategyProperties.getEngine().setEnabled(true);
        strategyProperties.getEngine().setRequireMidSumSane(true);
        strategyProperties.getEngine().setActiveStrategyIds(List.of(STRATEGY_ID));
        strategyProperties.setStrategies(List.of(strategy(true)));
    }

    @Test
    void partialDonePositionRoutesExitThroughStrategyV2Engine() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-v2-entry", "4.545453");
        StrategyRuntimeState before = runtimeState();
        assertThat(before.currentTradeStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(before.activeEntryOrder()).isNull();
        assertThat(before.filledShares()).isEqualByComparingTo("4.545453");

        executor.resetCommands();
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "remote-v2-exit",
                new BigDecimal("0.54"),
                new BigDecimal("4.545453"),
                new BigDecimal("2.45454462"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T12:00:10Z")
        ));
        stubCurrentMarket();

        engine.tick();

        assertThat(executor.submittedCommands()).hasSize(1);
        assertThat(executor.lastSubmittedCommand().side()).isEqualTo(TradeSide.SELL);
        assertThat(executor.lastSubmittedCommand().shares()).isEqualByComparingTo("4.545453");

        StrategyRuntimeState after = runtimeState();
        assertThat(tradeRepository.findById(partial.tradeId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(after.hasPosition()).isFalse();
        assertThat(after.currentTradeStatus()).isEqualTo(TradeStatus.NEW);
        assertThat(tradeRepository.count()).isEqualTo(1);
        assertThat(tradeOrderRepository.findByTradeId(partial.tradeId())).hasSize(2);
        assertThat(latestOrder(partial.tradeId(), TradeOrderPhase.EXIT).getTradeId()).isEqualTo(partial.tradeId());
    }

    @Test
    void partialDonePositionDoesNotExitWhenPartialExitDisabled() {
        createPartialDonePosition("remote-v2-entry-disabled", "4.545453");
        strategyProperties.setStrategies(List.of(strategy(false)));
        executor.resetCommands();
        stubCurrentMarket();

        engine.tick();

        assertThat(executor.submittedCommands()).isEmpty();
        assertThat(tradeOrderRepository.findAll()).hasSize(1);
        StrategyRuntimeState state = runtimeState();
        assertThat(state.currentTradeStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(state.activeEntryOrder()).isNull();
    }

    @Test
    void partiallyClosedPositionRoutesRemainingExitThroughStrategyV2Engine() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-v2-partial-close-entry", "4.5");
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "remote-v2-direct-exit",
                new BigDecimal("0.54"),
                new BigDecimal("2.0"),
                new BigDecimal("1.08"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T12:00:08Z")
        ));
        orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "2.0", "0.54"), ExecutionMode.LIVE);
        assertThat(runtimeState().currentTradeStatus()).isEqualTo(TradeStatus.PARTIALLY_CLOSED);

        executor.resetCommands();
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "remote-v2-engine-exit",
                new BigDecimal("0.54"),
                new BigDecimal("2.5"),
                new BigDecimal("1.35"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T12:00:10Z")
        ));
        stubCurrentMarket();

        engine.tick();

        assertThat(executor.submittedCommands()).hasSize(1);
        assertThat(executor.lastSubmittedCommand().side()).isEqualTo(TradeSide.SELL);
        assertThat(executor.lastSubmittedCommand().shares()).isEqualByComparingTo("2.5");
        assertThat(tradeRepository.count()).isEqualTo(1);
        assertThat(tradeRepository.findById(partial.tradeId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.CLOSED);
    }

    @Test
    void partiallyClosedPositionDoesNotCreateDuplicateBuy() {
        OrderLifecycleResult partial = createPartialDonePosition("remote-v2-no-buy-entry", "4.5");
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "remote-v2-no-buy-direct-exit",
                new BigDecimal("0.54"),
                new BigDecimal("2.0"),
                new BigDecimal("1.08"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T12:00:08Z")
        ));
        orderManager.submitOrder(sellIntent(TradeOrderType.FAK, "2.0", "0.54"), ExecutionMode.LIVE);

        strategyProperties.setStrategies(List.of(strategyWithExitThreshold(false, "0.60")));
        executor.resetCommands();
        stubCurrentMarket();

        engine.tick();

        assertThat(executor.submittedCommands()).isEmpty();
        assertThat(tradeRepository.count()).isEqualTo(1);
        StrategyRuntimeState state = runtimeState();
        assertThat(state.currentTradeStatus()).isEqualTo(TradeStatus.PARTIALLY_CLOSED);
        assertThat(state.activeEntryOrder()).isNull();
        assertThat(tradeOrderRepository.findByTradeId(partial.tradeId())).hasSize(2);
    }

    @Test
    void partiallyOpenWithActiveEntryOrderDoesNotExitUntilRemainderDoneUnlessConfigured() {
        OrderLifecycleResult partial = createPartialLivePosition("remote-v2-live-entry", "5", "1");
        executor.resetCommands();
        stubCurrentMarket();

        engine.tick();

        assertThat(executor.submittedCommands()).isEmpty();
        StrategyRuntimeState state = runtimeState();
        assertThat(state.currentTradeStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(state.activeEntryOrder()).isNotNull();
        assertThat(state.activeExitOrder()).isNull();
        assertThat(tradeRepository.count()).isEqualTo(1);
        assertThat(latestOrder(partial.tradeId(), TradeOrderPhase.ENTRY).getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
    }

    private OrderLifecycleResult createPartialDonePosition(String remoteOrderId, String filledShares) {
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                false,
                "LIVE",
                remoteOrderId,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "accepted",
                "{}",
                Instant.parse("2026-05-23T12:00:01Z")
        ));
        executor.onGetOrderStatus(
                remoteOrderId,
                orderStatus(remoteOrderId, "OPEN", "0.50", "5", null, null, null),
                orderStatus(remoteOrderId, "CANCELED", "0.50", "5", filledShares, null, "0.50")
        );
        executor.onListFills(
                remoteOrderId,
                new ExecutorFillsResponse(true, List.of(), "[]", null),
                new ExecutorFillsResponse(true, List.of(fill(remoteOrderId, "fill-" + remoteOrderId, filledShares, "0.50")), "[]", null)
        );

        OrderLifecycleResult submitted = orderManager.submitOrder(
                TradeIntent.buy(
                        null,
                        market(),
                        outcomePrice("0.50", "0.51"),
                        new BigDecimal("2.50"),
                        new BigDecimal("5"),
                        TradeOrderType.GTD,
                        true,
                        new BigDecimal("0.50"),
                        STRATEGY_ID,
                        "entry-rule",
                        "strategy entry"
                ),
                ExecutionMode.LIVE
        );
        TradeOrderEntity entryOrder = latestOrder(submitted.tradeId(), TradeOrderPhase.ENTRY);
        orderManager.reconcileOrderDetailed(entryOrder.getId(), OrderReconciliationSource.AUTO_WORKER);
        return submitted;
    }

    private OrderLifecycleResult createPartialLivePosition(String remoteOrderId, String requestedShares, String filledShares) {
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                false,
                "LIVE",
                remoteOrderId,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "accepted",
                "{}",
                Instant.parse("2026-05-23T12:00:01Z")
        ));
        executor.onGetOrderStatus(
                remoteOrderId,
                orderStatus(remoteOrderId, "OPEN", "0.50", requestedShares, null, null, null),
                orderStatus(
                        remoteOrderId,
                        "OPEN",
                        "0.50",
                        requestedShares,
                        filledShares,
                        new BigDecimal(requestedShares).subtract(new BigDecimal(filledShares)).toPlainString(),
                        "0.50"
                )
        );
        executor.onListFills(
                remoteOrderId,
                new ExecutorFillsResponse(true, List.of(), "[]", null),
                new ExecutorFillsResponse(true, List.of(fill(remoteOrderId, "fill-" + remoteOrderId, filledShares, "0.50")), "[]", null)
        );

        OrderLifecycleResult submitted = orderManager.submitOrder(
                TradeIntent.buy(
                        null,
                        market(),
                        outcomePrice("0.50", "0.51"),
                        new BigDecimal(requestedShares).multiply(new BigDecimal("0.50")),
                        new BigDecimal(requestedShares),
                        TradeOrderType.GTC,
                        true,
                        new BigDecimal("0.50"),
                        STRATEGY_ID,
                        "entry-rule",
                        "strategy entry"
                ),
                ExecutionMode.LIVE
        );
        TradeOrderEntity entryOrder = latestOrder(submitted.tradeId(), TradeOrderPhase.ENTRY);
        orderManager.reconcileOrderDetailed(entryOrder.getId(), OrderReconciliationSource.AUTO_WORKER);
        return submitted;
    }

    private void stubCurrentMarket() {
        StrategyMarketView marketView = mock(StrategyMarketView.class);
        StrategyOutcomeView up = outcome("Up", "token-up", "0.58");
        StrategyOutcomeView down = outcome("Down", TOKEN_ID, "0.42");
        when(marketView.outcomes()).thenReturn(List.of(up, down));
        when(marketView.outcome("Up")).thenReturn(Optional.of(up));
        when(marketView.outcome("Down")).thenReturn(Optional.of(down));
        when(marketView.token("token-up")).thenReturn(Optional.of(up));
        when(marketView.token(TOKEN_ID)).thenReturn(Optional.of(down));
        when(trackedMarketState.currentMarket()).thenReturn(Optional.of(market()));
        when(marketDataProvider.currentUpDownMarket()).thenReturn(Optional.of(marketView));
    }

    private StrategyV2Properties.Strategy strategy(boolean allowExitPartialPosition) {
        return strategyWithExitThreshold(allowExitPartialPosition, "0.40");
    }

    private StrategyV2Properties.Strategy strategyWithExitThreshold(boolean allowExitPartialPosition, String exitThreshold) {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        strategy.setStrategyId(STRATEGY_ID);
        strategy.getPartialFillManagement().setAllowExitPartialPosition(allowExitPartialPosition);

        StrategyV2Properties.Action entryAction = new StrategyV2Properties.Action();
        entryAction.setOrderType(TradeOrderType.FOK.name());
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setUsd(new BigDecimal("1.00"));
        entryAction.setSize(size);
        strategy.getEntry().setAction(entryAction);

        StrategyV2Properties.ExitRule exitRule = new StrategyV2Properties.ExitRule();
        exitRule.setName("exit-rule");
        exitRule.setAction("SELL_NOW");
        exitRule.setOrderType("FAK");
        StrategyV2Properties.Condition condition = new StrategyV2Properties.Condition();
        condition.setFeature("candidate.mid");
        condition.setOp(">=");
        condition.setValue(exitThreshold);
        exitRule.setWhen(condition);
        strategy.getExit().setRules(List.of(exitRule));
        return strategy;
    }

    private TradeIntent sellIntent(TradeOrderType orderType, String shares, String price) {
        return TradeIntent.sell(
                null,
                market(),
                outcomePrice(price, new BigDecimal(price).add(new BigDecimal("0.01")).toPlainString()),
                new BigDecimal(shares),
                orderType,
                orderType.prefersMaker(),
                new BigDecimal(price),
                STRATEGY_ID,
                "exit-rule",
                "strategy exit"
        );
    }

    private StrategyOutcomeView outcome(String outcome, String tokenId, String mid) {
        StrategyOutcomeView view = mock(StrategyOutcomeView.class);
        when(view.outcome()).thenReturn(outcome);
        when(view.tokenId()).thenReturn(tokenId);
        when(view.mid()).thenReturn(new BigDecimal(mid));
        when(view.spread()).thenReturn(new BigDecimal("0.02"));
        when(view.priceAgeMs()).thenReturn(Optional.empty());
        when(view.bookAgeMs()).thenReturn(Optional.empty());
        when(view.bidDepth()).thenReturn(BigDecimal.ZERO);
        when(view.askDepth()).thenReturn(BigDecimal.ZERO);
        when(view.bidDepthWithin(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        when(view.askDepthWithin(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        when(view.bestBidLevel()).thenReturn(Optional.empty());
        when(view.bestAskLevel()).thenReturn(Optional.empty());
        when(view.estimateTakerBuy(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(view.estimateMakerBuyFee(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        return view;
    }

    private StrategyRuntimeState runtimeState() {
        return dbTradeStateProvider.getState(StrategyInstanceKey.of(null, STRATEGY_ID), MARKET_ID);
    }

    private TradeOrderEntity latestOrder(Long tradeId, TradeOrderPhase phase) {
        return tradeOrderRepository.findByTradeId(tradeId).stream()
                .filter(order -> order.getPhase() == phase)
                .max(Comparator.comparing(TradeOrderEntity::getId))
                .orElseThrow();
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                MARKET_ID,
                "Strategy V2 integration market",
                "condition-v2",
                "strategy-v2-integration-market",
                Instant.parse("2026-05-23T12:10:00Z"),
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

    private OutcomePrice outcomePrice(String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(
                TOKEN_ID,
                "Down",
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                Instant.parse("2026-05-23T12:00:00Z")
        );
    }

    private ExecutorOrderStatusResponse orderStatus(
            String remoteOrderId,
            String status,
            String price,
            String originalSize,
            String filledSize,
            String remainingSize,
            String avgFillPrice
    ) {
        return new ExecutorOrderStatusResponse(
                true,
                remoteOrderId,
                status,
                MARKET_ID,
                TOKEN_ID,
                TradeSide.BUY,
                price == null ? null : new BigDecimal(price),
                originalSize == null ? null : new BigDecimal(originalSize),
                filledSize == null ? null : new BigDecimal(filledSize),
                remainingSize == null ? null : new BigDecimal(remainingSize),
                avgFillPrice == null ? null : new BigDecimal(avgFillPrice),
                Instant.parse("2026-05-23T12:00:01Z"),
                Instant.parse("2026-05-23T12:00:02Z"),
                Instant.parse("2026-05-23T12:05:00Z"),
                "{}",
                null
        );
    }

    private ExecutorFillResponse fill(String remoteOrderId, String fillId, String shares, String price) {
        return new ExecutorFillResponse(
                remoteOrderId,
                "trade-" + fillId,
                fillId,
                TOKEN_ID,
                MARKET_ID,
                TradeSide.BUY,
                new BigDecimal(price),
                new BigDecimal(shares),
                new BigDecimal("0.01"),
                LiquidityRole.MAKER,
                Instant.parse("2026-05-23T12:00:03Z"),
                "{}"
        );
    }

    @TestConfiguration
    static class MockMarketConfig {
        @Bean
        @Primary
        StrategyMarketDataProvider strategyMarketDataProvider() {
            return mock(StrategyMarketDataProvider.class);
        }

        @Bean
        @Primary
        TrackedMarketState trackedMarketState() {
            return mock(TrackedMarketState.class);
        }
    }
}
