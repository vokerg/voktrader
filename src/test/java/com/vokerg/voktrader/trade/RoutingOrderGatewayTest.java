package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.paper.PaperOrderGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingOrderGatewayTest {
    private final TradingProperties tradingProperties = new TradingProperties();
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final LiveOrderGateway liveOrderGateway = mock(LiveOrderGateway.class);
    private final PaperOrderGateway paperOrderGateway = mock(PaperOrderGateway.class);
    private final RoutingOrderGateway gateway = new RoutingOrderGateway(
            tradingProperties,
            executionProperties,
            liveOrderGateway,
            paperOrderGateway
    );

    @Test
    void paperModeUsesPaperGatewayWhenOrderLayerEnabled() {
        tradingProperties.setMode(ExecutionMode.PAPER);
        executionProperties.setUseOrderLayer(true);
        TradeIntent intent = mock(TradeIntent.class);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "MK_GTD_EDGE_A");
        OrderLifecycleResult result = new OrderLifecycleResult(
                true,
                1L,
                2L,
                "paper-local",
                null,
                TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.RESTING,
                "paper resting",
                null
        );
        when(paperOrderGateway.submitOrder(intent, owner, ExecutionMode.PAPER)).thenReturn(result);

        OrderLifecycleResult routed = gateway.submitOrder(intent, owner, ExecutionMode.PAPER);

        assertThat(routed).isSameAs(result);
        verify(paperOrderGateway).submitOrder(intent, owner, ExecutionMode.PAPER);
        verify(liveOrderGateway, never()).submitOrder(intent, owner, ExecutionMode.PAPER);
    }

    @Test
    void liveModeUsesLiveGateway() {
        tradingProperties.setMode(ExecutionMode.LIVE);
        executionProperties.setUseOrderLayer(true);
        TradeIntent intent = mock(TradeIntent.class);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "MK_GTD_EDGE_A");
        OrderLifecycleResult result = new OrderLifecycleResult(
                true,
                1L,
                2L,
                "live-local",
                "remote-1",
                TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.SUBMITTED,
                "live submitted",
                null
        );
        when(liveOrderGateway.submitOrder(intent, owner, ExecutionMode.LIVE)).thenReturn(result);

        OrderLifecycleResult routed = gateway.submitOrder(intent, owner, ExecutionMode.LIVE);

        assertThat(routed).isSameAs(result);
        verify(liveOrderGateway).submitOrder(intent, owner, ExecutionMode.LIVE);
        verify(paperOrderGateway, never()).submitOrder(intent, owner, ExecutionMode.LIVE);
    }

    @Test
    void rawPaperBuyCannotBypassCentralEntryRisk() {
        TradeIntent intent = mock(TradeIntent.class);
        when(intent.side()).thenReturn(TradeSide.BUY);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "MK_GTD_EDGE_A");

        OrderLifecycleResult result = gateway.submitOrder(intent, owner, ExecutionMode.PAPER);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("approved central entry risk decision is required");
        verify(paperOrderGateway, never()).submitOrder(intent, owner, ExecutionMode.PAPER);
        verify(liveOrderGateway, never()).submitOrder(intent, owner, ExecutionMode.PAPER);
    }

    @Test
    void rawLiveBuyIsRejectedBeforeSelectingADelegate() {
        TradeIntent intent = mock(TradeIntent.class);
        when(intent.side()).thenReturn(TradeSide.BUY);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "MK_GTD_EDGE_A");

        OrderLifecycleResult result = gateway.submitOrder(intent, owner, ExecutionMode.LIVE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("approved central entry risk decision is required");
        verify(liveOrderGateway, never()).submitOrder(intent, owner, ExecutionMode.LIVE);
        verify(paperOrderGateway, never()).submitOrder(intent, owner, ExecutionMode.LIVE);
    }
}
