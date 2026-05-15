package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyV2OrderActionBuilderTest {
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final OrderGateway orderGateway = mock(OrderGateway.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final StrategyV2OrderActionBuilder builder = new StrategyV2OrderActionBuilder(
            executionProperties,
            executionRouter,
            orderGateway,
            tradingProperties
    );

    @Test
    void flagDisabledRoutesThroughExistingExecutionRouter() {
        when(executionRouter.route(any(TradeIntent.class))).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.LIVE,
                1L,
                2L,
                TradeStatus.OPEN,
                TradeOrderStatus.FILLED,
                "accepted"
        ));

        TradeExecutionResult result = builder.routeEntry(strategy(TradeOrderType.FOK), context());

        assertThat(result.accepted()).isTrue();
        verify(executionRouter).route(any(TradeIntent.class));
        verify(orderGateway, never()).submitOrder(any(), any(), any());
    }

    @Test
    void flagEnabledRoutesThroughOrderManager() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(TradeIntent.class), any(), any())).thenReturn(new OrderLifecycleResult(
                true,
                1L,
                2L,
                "local-1",
                "remote-1",
                TradeStatus.OPEN,
                TradeOrderStatus.FILLED,
                "filled",
                null
        ));

        TradeExecutionResult result = builder.routeEntry(strategy(TradeOrderType.FOK), context());

        assertThat(result.accepted()).isTrue();
        assertThat(result.localOrderId()).isEqualTo("local-1");
        assertThat(result.remoteOrderId()).isEqualTo("remote-1");
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.FILLED);
        verify(orderGateway).submitOrder(any(TradeIntent.class), any(), any());
        verify(executionRouter, never()).route(any());
    }

    @Test
    void flagEnabledPreservesNonImmediateSubmittedResult() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(TradeIntent.class), any(), any())).thenReturn(new OrderLifecycleResult(
                true,
                1L,
                2L,
                "local-1",
                "remote-1",
                TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.SUBMITTED,
                "submitted",
                null
        ));

        TradeExecutionResult result = builder.routeEntry(strategy(TradeOrderType.GTC), context());

        assertThat(result.accepted()).isTrue();
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.SUBMITTED);

        ArgumentCaptor<TradeIntent> intent = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderGateway).submitOrder(intent.capture(), any(), any());
        assertThat(intent.getValue().orderType()).isEqualTo(TradeOrderType.GTC);
    }

    @Test
    void fixedSharesSizeSetsRequestedSharesAndNotionalFromLimitPrice() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(TradeIntent.class), any(), any())).thenReturn(new OrderLifecycleResult(
                true,
                1L,
                2L,
                "local-1",
                "remote-1",
                TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.RESTING,
                "resting",
                null
        ));

        builder.routeEntry(fixedSharesStrategy(), context());

        ArgumentCaptor<TradeIntent> intent = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderGateway).submitOrder(intent.capture(), any(), any());
        assertThat(intent.getValue().shares()).isEqualByComparingTo("5.00");
        assertThat(intent.getValue().amountUsd()).isEqualByComparingTo("2.55");
    }

    @Test
    void makerFixedSharesUsesConfiguredMinimumWhenSharesOmitted() {
        tradingProperties.setMinMakerOrderShares(new BigDecimal("6.00"));
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(TradeIntent.class), any(), any())).thenReturn(new OrderLifecycleResult(
                true,
                1L,
                2L,
                "local-1",
                "remote-1",
                TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.RESTING,
                "resting",
                null
        ));

        StrategyV2Properties.Strategy strategy = fixedSharesStrategy();
        strategy.getEntry().getAction().getSize().setShares(null);

        builder.routeEntry(strategy, context());

        ArgumentCaptor<TradeIntent> intent = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderGateway).submitOrder(intent.capture(), any(), any());
        assertThat(intent.getValue().shares()).isEqualByComparingTo("6.00");
        assertThat(intent.getValue().amountUsd()).isEqualByComparingTo("3.06");
    }


    @Test
    void flagEnabledSurfacesRejectedOrderManagerResult() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(TradeIntent.class), any(), any())).thenReturn(new OrderLifecycleResult(
                false,
                1L,
                2L,
                "local-1",
                null,
                TradeStatus.FAILED,
                TradeOrderStatus.REJECTED,
                "rejected",
                "rejected"
        ));

        TradeExecutionResult result = builder.routeEntry(strategy(TradeOrderType.FOK), context());

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("rejected");
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.REJECTED);
    }

    @Test
    void defaultConfigKeepsOrderLayerDisabled() {
        assertThat(new StrategyV2ExecutionProperties().isUseOrderLayer()).isFalse();
    }

    private StrategyV2Properties.Strategy strategy(TradeOrderType orderType) {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        strategy.setStrategyId("strategy-v2-test");
        StrategyV2Properties.Entry entry = new StrategyV2Properties.Entry();
        entry.setRuleId("entry");
        StrategyV2Properties.Action action = new StrategyV2Properties.Action();
        action.setOrderType(orderType.name());
        action.setPostOnly(orderType.canRestOnBook());
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setUsd(new BigDecimal("1.00"));
        action.setSize(size);
        entry.setAction(action);
        strategy.setEntry(entry);
        return strategy;
    }

    private StrategyV2Properties.Strategy fixedSharesStrategy() {
        StrategyV2Properties.Strategy strategy = strategy(TradeOrderType.GTD);
        StrategyV2Properties.Action action = strategy.getEntry().getAction();
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setType("fixed_shares");
        size.setShares(new BigDecimal("5.00"));
        action.setSize(size);
        return strategy;
    }

    private StrategyV2FeatureContext context() {
        StrategyOutcomeView candidate = mock(StrategyOutcomeView.class);
        when(candidate.tokenId()).thenReturn("token-id");
        when(candidate.outcome()).thenReturn("Up");
        when(candidate.spread()).thenReturn(new BigDecimal("0.02"));
        Map<String, Object> features = new HashMap<>();
        features.put("candidate.bid", new BigDecimal("0.49"));
        features.put("candidate.ask", new BigDecimal("0.51"));
        return new StrategyV2FeatureContext(market(), null, candidate, null, Instant.parse("2026-05-09T12:00:00Z"), features);
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "Question",
                "condition-id",
                "slug",
                Instant.parse("2026-05-09T12:05:00Z"),
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
}
