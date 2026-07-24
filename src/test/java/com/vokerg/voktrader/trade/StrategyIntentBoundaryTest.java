package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyIntentBoundaryTest {
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final OrderGateway orderGateway = mock(OrderGateway.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final StrategyIntentBoundary boundary = new StrategyIntentBoundary(
            executionProperties,
            executionRouter,
            orderGateway,
            tradingProperties
    );

    @BeforeEach
    void setUp() {
        tradingProperties.setMode(ExecutionMode.PAPER);
    }

    @Test
    void compatibilityModeRoutesTypedEntryThroughExecutionRouter() {
        when(executionRouter.route(any())).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER, 1L, 2L, TradeStatus.OPEN, TradeOrderStatus.FILLED, "accepted"));

        TradeExecutionResult result = boundary.accept(entryIntent());

        assertThat(result.accepted()).isTrue();
        ArgumentCaptor<TradeIntent> routed = ArgumentCaptor.forClass(TradeIntent.class);
        verify(executionRouter).route(routed.capture());
        verify(orderGateway, never()).submitOrder(any(), any(), any());
        assertThat(routed.getValue().side()).isEqualTo(TradeSide.BUY);
    }

    @Test
    void orderLayerModeRoutesTypedEntryThroughGateway() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(), any(), any())).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "local-1", "remote-1", TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.SUBMITTED, "submitted", null));

        TradeExecutionResult result = boundary.accept(entryIntent());

        assertThat(result.accepted()).isTrue();
        assertThat(result.localOrderId()).isEqualTo("local-1");
        verify(orderGateway).submitOrder(any(), any(), any());
        verify(executionRouter, never()).route(any());
    }

    @Test
    void orderLayerModeRoutesTypedExitWithoutEntryTypeConfusion() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(), any(), any())).thenReturn(new OrderLifecycleResult(
                true, 3L, 4L, "local-exit", "remote-exit", TradeStatus.EXIT_PENDING,
                TradeOrderStatus.SUBMITTED, "submitted", null));

        boundary.submit(exitIntent());

        ArgumentCaptor<TradeIntent> routed = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderGateway).submitOrder(routed.capture(), any(), any());
        assertThat(routed.getValue().side()).isEqualTo(TradeSide.SELL);
        assertThat(routed.getValue().shares()).isEqualByComparingTo("2.00");
    }

    private EntryIntent entryIntent() {
        return EntryIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "strategy-v2-test",
                "entry",
                "test entry"
        );
    }

    private ExitIntent exitIntent() {
        return ExitIntent.sell(
                market(),
                price(),
                new BigDecimal("2.00"),
                "strategy-v2-test",
                "exit",
                "test exit"
        );
    }

    private OutcomePrice price() {
        return new OutcomePrice(
                "token-id",
                "Up",
                new BigDecimal("0.49"),
                new BigDecimal("0.51"),
                new BigDecimal("0.02"),
                Instant.parse("2026-07-24T12:00:00Z")
        );
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "Question",
                "condition-id",
                "slug",
                Instant.parse("2026-07-24T12:15:00Z"),
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
