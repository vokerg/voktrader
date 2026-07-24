package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveArmServiceTest {
    @Test
    void capabilityReadyProcessStillStartsUnarmedAndArmExpires() {
        TradingProperties trading = readyTradingProperties();
        ExecutorProperties executor = readyExecutorProperties();
        LiveArmService service = new LiveArmService(trading, executor);
        Instant startedAt = Instant.parse("2026-07-24T06:00:00Z");

        TimeMachine.runAt(startedAt, () -> {
            LiveArmService.LiveArmStatus initial = service.status();
            assertThat(initial.capabilityReady()).isTrue();
            assertThat(initial.armed()).isFalse();
            assertThat(initial.entryAllowed()).isFalse();
            assertThat(initial.entryBlockers()).containsExactly("live arm is not active");

            LiveArmService.LiveArmStatus armed = service.arm("0xexpected");
            assertThat(armed.armed()).isTrue();
            assertThat(armed.entryAllowed()).isTrue();
            assertThat(armed.armedAt()).isEqualTo(startedAt);
            assertThat(armed.expiresAt()).isEqualTo(startedAt.plus(Duration.ofMinutes(5)));
            assertThat(armed.armedAccountId()).isEqualTo("0xexpected");
        });

        TimeMachine.runAt(startedAt.plus(Duration.ofMinutes(5)), () -> {
            LiveArmService.LiveArmStatus expired = service.status();
            assertThat(expired.armed()).isFalse();
            assertThat(expired.entryAllowed()).isFalse();
            assertThat(expired.entryBlockReason()).contains("live arm expired at 2026-07-24T06:05:00Z");
        });
    }

    @Test
    void defaultExecutorTokenAndMissingAccountBlockArming() {
        TradingProperties trading = readyTradingProperties();
        trading.setExpectedAccountId("");
        ExecutorProperties executor = readyExecutorProperties();
        executor.setApiToken("change-me");
        LiveArmService service = new LiveArmService(trading, executor);

        LiveArmService.LiveArmStatus status = service.status();

        assertThat(status.capabilityReady()).isFalse();
        assertThat(status.executorTokenConfigured()).isFalse();
        assertThat(status.expectedAccountConfigured()).isFalse();
        assertThat(status.capabilityBlockers()).contains(
                "executor API token is blank or still uses the default value",
                "expected live account metadata is not configured"
        );
        assertThatThrownBy(() -> service.arm("0xexpected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIVE capability is not ready");
    }

    @Test
    void armRequiresConfiguredAccountIdentityToMatch() {
        LiveArmService service = new LiveArmService(readyTradingProperties(), readyExecutorProperties());

        assertThatThrownBy(() -> service.arm("0xother"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThat(service.status().armed()).isFalse();
    }

    @Test
    void killSwitchRemainsASeparateBlockingGate() {
        TradingProperties trading = readyTradingProperties();
        trading.setKillSwitchEnabled(true);
        LiveArmService service = new LiveArmService(trading, readyExecutorProperties());

        LiveArmService.LiveArmStatus status = service.status();

        assertThat(status.capabilityReady()).isFalse();
        assertThat(status.capabilityBlockers()).contains("kill switch is enabled");
        assertThat(status.entryAllowed()).isFalse();
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
