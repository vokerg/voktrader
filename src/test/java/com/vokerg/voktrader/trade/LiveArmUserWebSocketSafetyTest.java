package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.polymarket.user.UserWebSocketHealthService;
import com.vokerg.voktrader.polymarket.user.UserWebSocketSafetyService;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveArmUserWebSocketSafetyTest {
    @Test
    void disconnectedPrivateStreamBlocksArmingWhenLiveExchangeStateExists() {
        UserWebSocketSafetyService safety = mock(UserWebSocketSafetyService.class);
        when(safety.requiresHealthyStream()).thenReturn(true);

        UserWebSocketHealthService health = mock(UserWebSocketHealthService.class);
        when(health.snapshot()).thenReturn(new UserWebSocketHealthService.Snapshot(
                true,
                true,
                false,
                UserWebSocketHealthService.State.DISCONNECTED,
                2L,
                null,
                null,
                null,
                null,
                "socket disconnected"
        ));

        LiveArmService service = new LiveArmService(
                readyTradingProperties(),
                readyExecutorProperties(),
                safety,
                health
        );

        assertThat(service.status().capabilityReady()).isFalse();
        assertThat(service.status().capabilityBlockers())
                .contains("authenticated user websocket is not healthy while live orders or provisional fills exist");
    }

    @Test
    void cleanAccountDoesNotRequirePrivateStreamForArmingAtThisPhase() {
        UserWebSocketSafetyService safety = mock(UserWebSocketSafetyService.class);
        when(safety.requiresHealthyStream()).thenReturn(false);

        UserWebSocketHealthService health = mock(UserWebSocketHealthService.class);
        LiveArmService service = new LiveArmService(
                readyTradingProperties(),
                readyExecutorProperties(),
                safety,
                health
        );

        assertThat(service.status().capabilityReady()).isTrue();
    }

    private TradingProperties readyTradingProperties() {
        TradingProperties trading = new TradingProperties();
        trading.setMode(ExecutionMode.LIVE);
        trading.setLiveEnabled(true);
        trading.setKillSwitchEnabled(false);
        trading.setExpectedAccountId("0xexpected");
        trading.setLiveArmTtl(Duration.ofMinutes(5));
        return trading;
    }

    private ExecutorProperties readyExecutorProperties() {
        ExecutorProperties executor = new ExecutorProperties();
        executor.setEnabled(true);
        executor.setDryRun(false);
        executor.setApiToken("non-default-token");
        return executor;
    }
}
