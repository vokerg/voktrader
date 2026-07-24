package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LiveOrderGatewayTest {
    private final OrderManager orderManager = mock(OrderManager.class);
    private final LiveArmService liveArmService = mock(LiveArmService.class);
    private final LiveOrderGateway gateway = new LiveOrderGateway(orderManager, liveArmService);

    @Test
    void unarmedLiveBuyIsRejectedBeforeOrderManagerSubmission() {
        TradeIntent intent = mock(TradeIntent.class);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "strategy");
        when(intent.side()).thenReturn(TradeSide.BUY);
        when(liveArmService.status()).thenReturn(unarmedStatus());

        OrderLifecycleResult result = gateway.submitOrder(intent, owner, ExecutionMode.LIVE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("LIVE entry rejected before executor call", "live arm is not active");
        verify(orderManager, never()).submitOrder(intent, ExecutionMode.LIVE);
    }

    @Test
    void armedLiveBuyDelegatesToOrderManager() {
        TradeIntent intent = mock(TradeIntent.class);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "strategy");
        OrderLifecycleResult accepted = new OrderLifecycleResult(true, 1L, 2L, "local", "remote", null, null, "accepted", null);
        when(intent.side()).thenReturn(TradeSide.BUY);
        when(liveArmService.status()).thenReturn(armedStatus());
        when(orderManager.submitOrder(intent, ExecutionMode.LIVE)).thenReturn(accepted);

        OrderLifecycleResult result = gateway.submitOrder(intent, owner, ExecutionMode.LIVE);

        assertThat(result).isSameAs(accepted);
        verify(orderManager).submitOrder(intent, ExecutionMode.LIVE);
    }

    @Test
    void unarmedLiveSellStillDelegatesToOrderManager() {
        TradeIntent intent = mock(TradeIntent.class);
        StrategyInstanceKey owner = StrategyInstanceKey.of(1L, "strategy");
        OrderLifecycleResult accepted = new OrderLifecycleResult(true, 1L, 2L, "local", "remote", null, null, "accepted", null);
        when(intent.side()).thenReturn(TradeSide.SELL);
        when(orderManager.submitOrder(intent, ExecutionMode.LIVE)).thenReturn(accepted);

        OrderLifecycleResult result = gateway.submitOrder(intent, owner, ExecutionMode.LIVE);

        assertThat(result).isSameAs(accepted);
        verify(orderManager).submitOrder(intent, ExecutionMode.LIVE);
        verifyNoInteractions(liveArmService);
    }

    @Test
    void cancellationRemainsAvailableWithoutLiveArm() {
        OrderLifecycleResult cancelled = new OrderLifecycleResult(true, 1L, 2L, "local", "remote", null, null, "cancelled", null);
        when(orderManager.cancelOrder("local", "operator cancel")).thenReturn(cancelled);

        OrderLifecycleResult result = gateway.cancelOrder("local", "operator cancel");

        assertThat(result).isSameAs(cancelled);
        verify(orderManager).cancelOrder("local", "operator cancel");
        verifyNoInteractions(liveArmService);
    }

    private LiveArmService.LiveArmStatus unarmedStatus() {
        return new LiveArmService.LiveArmStatus(
                false,
                null,
                null,
                null,
                true,
                true,
                true,
                false,
                List.of(),
                List.of("live arm is not active")
        );
    }

    private LiveArmService.LiveArmStatus armedStatus() {
        return new LiveArmService.LiveArmStatus(
                true,
                Instant.parse("2026-07-24T06:00:00Z"),
                Instant.parse("2026-07-24T06:15:00Z"),
                "0xexpected",
                true,
                true,
                true,
                true,
                List.of(),
                List.of()
        );
    }
}
