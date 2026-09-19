package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.executor.ExecutorCapabilityService;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.LiveArmService;
import com.vokerg.voktrader.trade.OrderLayerProperties;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.vokerg.voktrader.api.runtime.RiskGateStatus.GateState.BLOCKING;
import static com.vokerg.voktrader.api.runtime.RiskGateStatus.GateState.NOT_APPLICABLE;
import static com.vokerg.voktrader.api.runtime.RiskGateStatus.GateState.PASSING;
import static com.vokerg.voktrader.api.runtime.RiskGateStatus.GateState.UNAVAILABLE;

@Service
public class RiskGateStatusService {
    private final TradingProperties tradingProperties;
    private final ExecutorProperties executorProperties;
    private final OrderLayerProperties orderLayerProperties;
    private final StrategyV2ExecutionProperties executionProperties;

    public RiskGateStatusService(
            TradingProperties tradingProperties,
            ExecutorProperties executorProperties,
            OrderLayerProperties orderLayerProperties,
            StrategyV2ExecutionProperties executionProperties
    ) {
        this.tradingProperties = tradingProperties;
        this.executorProperties = executorProperties;
        this.orderLayerProperties = orderLayerProperties;
        this.executionProperties = executionProperties;
    }

    public RiskGateStatus status(
            LiveArmService.LiveArmStatus arm,
            ExecutorCapabilityService.ExecutorCapabilityReport executorCapabilities
    ) {
        ExecutionMode mode = tradingProperties.getMode();
        boolean live = mode == ExecutionMode.LIVE;
        List<RiskGateStatus.Gate> gates = new ArrayList<>();
        List<String> currentBlockers = new ArrayList<>();
        List<String> safeLiveBlockers = new ArrayList<>();

        gates.add(gate("TRADING_MODE", PASSING, true, false,
                "effective mode is " + mode));

        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "KILL_SWITCH", live, !tradingProperties.isKillSwitchEnabled(), true,
                "kill switch is disabled", "kill switch is enabled");
        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "LIVE_ENABLED", live, tradingProperties.isLiveEnabled(), true,
                "live capability is enabled", "live capability is disabled");
        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "ACCOUNT_PREFLIGHT", live, arm.expectedAccountConfigured(), true,
                "expected live account metadata is configured",
                "expected live account metadata is missing or default");
        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "EXECUTOR_TOKEN", live, arm.executorTokenConfigured(), true,
                "executor token is non-default", "executor token is missing or default");
        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "LIVE_ARM", live, arm.armed() && arm.entryAllowed(), true,
                "live arm is active until " + arm.expiresAt(),
                arm.entryBlockReason() == null ? "live arm is not active" : arm.entryBlockReason());

        boolean executorReady = executorProperties.isEnabled()
                && !executorProperties.isDryRun()
                && executorCapabilities != null
                && executorCapabilities.compatible();
        String executorDetail = executorCapabilities == null
                ? "executor capability evidence is unavailable"
                : executorCapabilities.compatible()
                        ? "executor identity and SDK contract are compatible"
                        : String.join("; ", executorCapabilities.blockers());
        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "EXECUTOR_IDENTITY", live, executorReady, false,
                executorDetail,
                executorDetail.isBlank() ? "executor identity is not ready" : executorDetail);

        String path = executionProperties.isUseOrderLayer()
                ? "ORDER_LAYER"
                : "COMPATIBILITY_DIRECT";
        boolean orderLayerConsistent = !executionProperties.isUseOrderLayer() || orderLayerProperties.isEnabled();
        addBooleanGate(gates, currentBlockers, safeLiveBlockers,
                "ENTRY_EXECUTION_PATH", true, orderLayerConsistent, true,
                "entry path is " + path,
                "Strategy V2 selects the order layer but voktrader.order-layer.enabled is false");

        gates.add(gate("PORTFOLIO_POLICY", PASSING, true, false,
                "onePositionPerBotMarket=" + tradingProperties.isOnePositionPerBotMarket()
                        + ", onePositionPerToken=" + tradingProperties.isOnePositionPerToken()
                        + ", maxActivePositionsPerMarket=" + tradingProperties.getMaxActivePositionsPerMarket()
                        + ", maxActivePositionsPerPortfolio=" + tradingProperties.getMaxActivePositionsPerPortfolio()
                        + "; candidate exposure is evaluated per intent"));

        if (live) {
            addUnavailableGate(gates, safeLiveBlockers, "DURABLE_OUTBOX",
                    "durable outbox submission is not implemented until T020/T021");
            addUnavailableGate(gates, safeLiveBlockers, "USER_WEBSOCKET",
                    "authenticated user WebSocket readiness is not implemented until T030");
        } else {
            gates.add(gate("DURABLE_OUTBOX", NOT_APPLICABLE, false, false,
                    "safe-live outbox readiness is not applicable in " + mode));
            gates.add(gate("USER_WEBSOCKET", NOT_APPLICABLE, false, false,
                    "authenticated live user WebSocket readiness is not applicable in " + mode));
        }

        boolean currentEntryAllowed = currentBlockers.isEmpty();
        boolean safeLiveReady = live && safeLiveBlockers.isEmpty();
        return new RiskGateStatus(
                mode.name(),
                path,
                currentEntryAllowed,
                safeLiveReady,
                gates,
                currentBlockers,
                safeLiveBlockers,
                "Price freshness, order size, spread, expiry, portfolio exposure, correlation, retry cooldown, and live-capacity checks are evaluated for each entry intent."
        );
    }

    private void addBooleanGate(
            List<RiskGateStatus.Gate> gates,
            List<String> currentBlockers,
            List<String> safeLiveBlockers,
            String id,
            boolean applicable,
            boolean passed,
            boolean enforced,
            String passingDetail,
            String blockingDetail
    ) {
        if (!applicable) {
            gates.add(gate(id, NOT_APPLICABLE, false, false, "not applicable outside LIVE mode"));
            return;
        }
        if (passed) {
            gates.add(gate(id, PASSING, enforced, false, passingDetail));
            return;
        }
        gates.add(gate(id, BLOCKING, enforced, true, blockingDetail));
        if (enforced) {
            currentBlockers.add(id + ": " + blockingDetail);
        }
        safeLiveBlockers.add(id + ": " + blockingDetail);
    }

    private void addUnavailableGate(
            List<RiskGateStatus.Gate> gates,
            List<String> safeLiveBlockers,
            String id,
            String detail
    ) {
        gates.add(gate(id, UNAVAILABLE, false, true, detail));
        safeLiveBlockers.add(id + ": " + detail);
    }

    private RiskGateStatus.Gate gate(
            String id,
            RiskGateStatus.GateState state,
            boolean enforced,
            boolean blocksSafeLive,
            String detail
    ) {
        return new RiskGateStatus.Gate(id, state, enforced, blocksSafeLive, detail);
    }
}
