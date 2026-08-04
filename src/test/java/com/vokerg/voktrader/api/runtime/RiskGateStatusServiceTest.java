package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.executor.ExecutorCapabilityService;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.LiveArmService;
import com.vokerg.voktrader.trade.OrderLayerProperties;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGateStatusServiceTest {
    @Test
    void liveStatusSeparatesCurrentlyEnforcedBlockersFromMissingSafeLiveControls() {
        TradingProperties trading = liveTrading();
        trading.setKillSwitchEnabled(true);
        ExecutorProperties executor = liveExecutor();
        OrderLayerProperties orderLayer = new OrderLayerProperties();
        StrategyV2ExecutionProperties execution = new StrategyV2ExecutionProperties();
        RiskGateStatusService service = new RiskGateStatusService(trading, executor, orderLayer, execution);

        RiskGateStatus status = service.status(
                new LiveArmService.LiveArmStatus(
                        false, null, null, null,
                        false, true, true, false,
                        List.of("kill switch is enabled"),
                        List.of("kill switch is enabled", "live arm is not active")
                ),
                compatibleExecutor()
        );

        assertThat(status.tradingMode()).isEqualTo("LIVE");
        assertThat(status.entryExecutionPath()).isEqualTo("COMPATIBILITY_DIRECT");
        assertThat(status.entryAllowedByCurrentStaticGates()).isFalse();
        assertThat(status.safeLiveReady()).isFalse();
        assertThat(status.currentEntryBlockers())
                .anyMatch(value -> value.startsWith("KILL_SWITCH:"))
                .anyMatch(value -> value.startsWith("LIVE_ARM:"))
                .noneMatch(value -> value.startsWith("DURABLE_OUTBOX:"));
        assertThat(status.safeLiveBlockers())
                .anyMatch(value -> value.startsWith("DURABLE_OUTBOX:"))
                .anyMatch(value -> value.startsWith("USER_WEBSOCKET:"));
        assertThat(gate(status, "DURABLE_OUTBOX")).satisfies(gate -> {
            assertThat(gate.state()).isEqualTo(RiskGateStatus.GateState.UNAVAILABLE);
            assertThat(gate.enforcedOnCurrentPath()).isFalse();
            assertThat(gate.blocksSafeLive()).isTrue();
        });
    }

    @Test
    void paperStatusMarksLiveOnlyGatesNotApplicableAndKeepsDynamicRiskVisible() {
        TradingProperties trading = new TradingProperties();
        trading.setMode(ExecutionMode.PAPER);
        ExecutorProperties executor = new ExecutorProperties();
        OrderLayerProperties orderLayer = new OrderLayerProperties();
        StrategyV2ExecutionProperties execution = new StrategyV2ExecutionProperties();
        RiskGateStatusService service = new RiskGateStatusService(trading, executor, orderLayer, execution);

        RiskGateStatus status = service.status(
                new LiveArmService.LiveArmStatus(
                        false, null, null, null,
                        false, false, false, false,
                        List.of(), List.of()
                ),
                ExecutorCapabilityService.ExecutorCapabilityReport.disabled()
        );

        assertThat(status.tradingMode()).isEqualTo("PAPER");
        assertThat(status.entryAllowedByCurrentStaticGates()).isTrue();
        assertThat(status.safeLiveReady()).isFalse();
        assertThat(status.currentEntryBlockers()).isEmpty();
        assertThat(status.safeLiveBlockers()).isEmpty();
        assertThat(gate(status, "KILL_SWITCH").state())
                .isEqualTo(RiskGateStatus.GateState.NOT_APPLICABLE);
        assertThat(gate(status, "DURABLE_OUTBOX").state())
                .isEqualTo(RiskGateStatus.GateState.NOT_APPLICABLE);
        assertThat(status.dynamicRiskNote()).contains("evaluated for each entry intent");
    }

    @Test
    void inconsistentOrderLayerSelectionIsAnEnforcedCurrentBlocker() {
        TradingProperties trading = new TradingProperties();
        trading.setMode(ExecutionMode.PAPER);
        ExecutorProperties executor = new ExecutorProperties();
        OrderLayerProperties orderLayer = new OrderLayerProperties();
        orderLayer.setEnabled(false);
        StrategyV2ExecutionProperties execution = new StrategyV2ExecutionProperties();
        execution.setUseOrderLayer(true);
        RiskGateStatusService service = new RiskGateStatusService(trading, executor, orderLayer, execution);

        RiskGateStatus status = service.status(
                new LiveArmService.LiveArmStatus(
                        false, null, null, null,
                        false, false, false, false,
                        List.of(), List.of()
                ),
                ExecutorCapabilityService.ExecutorCapabilityReport.disabled()
        );

        assertThat(status.entryExecutionPath()).isEqualTo("ORDER_LAYER");
        assertThat(status.entryAllowedByCurrentStaticGates()).isFalse();
        assertThat(status.currentEntryBlockers())
                .singleElement()
                .asString()
                .contains("ENTRY_EXECUTION_PATH");
    }

    private RiskGateStatus.Gate gate(RiskGateStatus status, String id) {
        return status.gates().stream()
                .filter(gate -> id.equals(gate.id()))
                .findFirst()
                .orElseThrow();
    }

    private TradingProperties liveTrading() {
        TradingProperties trading = new TradingProperties();
        trading.setMode(ExecutionMode.LIVE);
        trading.setLiveEnabled(true);
        trading.setExpectedAccountId("0xexpected");
        return trading;
    }

    private ExecutorProperties liveExecutor() {
        ExecutorProperties executor = new ExecutorProperties();
        executor.setEnabled(true);
        executor.setDryRun(false);
        executor.setApiToken("non-default-token");
        return executor;
    }

    private ExecutorCapabilityService.ExecutorCapabilityReport compatibleExecutor() {
        return new ExecutorCapabilityService.ExecutorCapabilityReport(
                true, true, "executor-api-v1", "0.3.0", "py-clob-client-v2", "1.1.0",
                List.of("FOK", "FAK", "GTC", "GTD"), List.of());
    }
}
