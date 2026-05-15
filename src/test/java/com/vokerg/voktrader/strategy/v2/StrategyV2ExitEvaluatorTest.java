package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyV2ExitEvaluatorTest {
    private final StrategyV2FeatureResolver featureResolver = new StrategyV2FeatureResolver();
    private final StrategyV2ConditionEvaluator conditionEvaluator = new StrategyV2ConditionEvaluator();
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final OrderGateway orderGateway = mock(OrderGateway.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final StrategyV2DiagnosticsRecorder diagnosticsRecorder = mock(StrategyV2DiagnosticsRecorder.class);
    private final StrategyV2OrderActionBuilder orderActionBuilder = new StrategyV2OrderActionBuilder(
            executionProperties,
            executionRouter,
            orderGateway,
            tradingProperties
    );
    private final StrategyV2ExitEvaluator evaluator = new StrategyV2ExitEvaluator(
            featureResolver,
            conditionEvaluator,
            orderActionBuilder,
            diagnosticsRecorder
    );

    @Test
    void openProfitTargetCreatesSellIntent() {
        givenRouteAccepted();

        evaluator.evaluate(strategy(">=", "0.05"), market(), marketView("0.58"), state(TradeStatus.OPEN, "10", true));

        TradeIntent intent = capturedIntent();
        assertThat(intent.side()).isEqualTo(TradeSide.SELL);
        assertThat(intent.shares()).isEqualByComparingTo("10");
    }

    @Test
    void openStopLossCreatesSellIntent() {
        givenRouteAccepted();

        evaluator.evaluate(strategy("<=", "-0.10"), market(), marketView("0.50"), state(TradeStatus.OPEN, "10", true));

        TradeIntent intent = capturedIntent();
        assertThat(intent.side()).isEqualTo(TradeSide.SELL);
        assertThat(intent.shares()).isEqualByComparingTo("10");
    }

    @Test
    void openNoRuleMatchDoesNotExit() {
        evaluator.evaluate(strategy(">=", "10.00"), market(), marketView("0.58"), state(TradeStatus.OPEN, "10", true));

        verify(executionRouter, never()).route(any());
    }

    @Test
    void entryPendingDoesNotExit() {
        evaluator.evaluate(strategy(">=", "0.05"), market(), marketView("0.58"), state(TradeStatus.ENTRY_PENDING, "10", true));

        verify(executionRouter, never()).route(any());
    }

    @Test
    void exitPendingDoesNotDuplicateExit() {
        evaluator.evaluate(strategy(">=", "0.05"), market(), marketView("0.58"), state(TradeStatus.EXIT_PENDING, "10", true));

        verify(executionRouter, never()).route(any());
    }

    @Test
    void partiallyOpenAllowedExitsFilledSharesOnly() {
        givenRouteAccepted();
        StrategyV2Properties.Strategy strategy = strategy(">=", "0.05");
        strategy.getPartialFillManagement().setAllowExitPartialPosition(true);

        evaluator.evaluate(strategy, market(), marketView("0.58"), state(TradeStatus.PARTIALLY_OPEN, "3.5", true));

        assertThat(capturedIntent().shares()).isEqualByComparingTo("3.5");
    }

    @Test
    void partiallyOpenNotAllowedDoesNotExit() {
        StrategyV2Properties.Strategy strategy = strategy(">=", "0.05");
        strategy.getPartialFillManagement().setAllowExitPartialPosition(false);

        evaluator.evaluate(strategy, market(), marketView("0.58"), state(TradeStatus.PARTIALLY_OPEN, "3.5", true));

        verify(executionRouter, never()).route(any());
    }

    @Test
    void unknownFeeUsesFallbackInsteadOfZero() {
        evaluator.evaluate(strategy(">=", "0.50"), market(), marketView("0.58"), state(TradeStatus.OPEN, "10", false));

        verify(executionRouter, never()).route(any());
    }

    @Test
    void orderLayerEnabledRoutesExitThroughOrderGateway() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(TradeIntent.class), any(), any())).thenReturn(new OrderLifecycleResult(
                true,
                1L,
                2L,
                "local-exit",
                "remote-exit",
                TradeStatus.EXIT_PENDING,
                TradeOrderStatus.SUBMITTED,
                "submitted",
                null
        ));

        evaluator.evaluate(strategy(">=", "0.05"), market(), marketView("0.58"), state(TradeStatus.OPEN, "10", true));

        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderGateway).submitOrder(captor.capture(), any(), any());
        verify(executionRouter, never()).route(any());
        assertThat(captor.getValue().side()).isEqualTo(TradeSide.SELL);
    }

    private void givenRouteAccepted() {
        when(executionRouter.route(any(TradeIntent.class))).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER,
                1L,
                2L,
                TradeStatus.EXIT_PENDING,
                TradeOrderStatus.FILLED,
                "accepted"
        ));
    }

    private TradeIntent capturedIntent() {
        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(executionRouter).route(captor.capture());
        return captor.getValue();
    }

    private StrategyV2Properties.Strategy strategy(String op, String value) {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        strategy.setStrategyId("strategy-test");
        StrategyV2Properties.ExitRule rule = new StrategyV2Properties.ExitRule();
        rule.setName("exit-rule");
        rule.setAction("SELL_NOW");
        rule.setOrderType("FOK");
        StrategyV2Properties.Condition condition = new StrategyV2Properties.Condition();
        condition.setFeature("trade.estimated_net_pnl_usd");
        condition.setOp(op);
        condition.setValue(value);
        rule.setWhen(condition);
        StrategyV2Properties.Exit exit = new StrategyV2Properties.Exit();
        exit.setRules(List.of(rule));
        strategy.setExit(exit);
        return strategy;
    }

    private StrategyRuntimeState state(TradeStatus status, String shares, boolean feeKnown) {
        return new StrategyRuntimeState(
                StrategyInstanceKey.of(null, "strategy-test"),
                "market-id",
                "strategy-test",
                "token-up",
                status,
                null,
                status == TradeStatus.EXIT_PENDING ? mock(OrderRuntimeState.class) : null,
                new BigDecimal(shares),
                BigDecimal.ZERO,
                new BigDecimal("0.50"),
                feeKnown ? BigDecimal.ZERO : null,
                feeKnown,
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-09T12:00:00Z"),
                null
        );
    }

    private StrategyMarketView marketView(String upMid) {
        StrategyMarketView marketView = mock(StrategyMarketView.class);
        StrategyOutcomeView up = outcome("Up", "token-up", upMid);
        StrategyOutcomeView down = outcome("Down", "token-down", "0.42");
        when(marketView.outcomes()).thenReturn(List.of(up, down));
        when(marketView.outcome("Up")).thenReturn(Optional.of(up));
        when(marketView.outcome("Down")).thenReturn(Optional.of(down));
        when(marketView.token("token-up")).thenReturn(Optional.of(up));
        when(marketView.token("token-down")).thenReturn(Optional.of(down));
        return marketView;
    }

    private StrategyOutcomeView outcome(String outcome, String tokenId, String mid) {
        StrategyOutcomeView view = mock(StrategyOutcomeView.class);
        when(view.outcome()).thenReturn(outcome);
        when(view.tokenId()).thenReturn(tokenId);
        when(view.mid()).thenReturn(new BigDecimal(mid));
        when(view.spread()).thenReturn(new BigDecimal("0.04"));
        when(view.priceAgeMs()).thenReturn(Optional.empty());
        when(view.bookAgeMs()).thenReturn(Optional.empty());
        when(view.bidDepth()).thenReturn(BigDecimal.ZERO);
        when(view.askDepth()).thenReturn(BigDecimal.ZERO);
        when(view.bidDepthWithin(any())).thenReturn(BigDecimal.ZERO);
        when(view.askDepthWithin(any())).thenReturn(BigDecimal.ZERO);
        when(view.bestBidLevel()).thenReturn(Optional.empty());
        when(view.bestAskLevel()).thenReturn(Optional.empty());
        when(view.estimateTakerBuy(any())).thenReturn(Optional.empty());
        when(view.estimateMakerBuyFee(any())).thenReturn(Optional.empty());
        return view;
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
