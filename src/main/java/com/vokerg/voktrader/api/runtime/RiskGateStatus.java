package com.vokerg.voktrader.api.runtime;

import java.util.List;

/** Operator-facing snapshot of the static entry gate chain. */
public record RiskGateStatus(
        String tradingMode,
        String entryExecutionPath,
        boolean entryAllowedByCurrentStaticGates,
        boolean safeLiveReady,
        List<Gate> gates,
        List<String> currentEntryBlockers,
        List<String> safeLiveBlockers,
        String dynamicRiskNote
) {
    public RiskGateStatus {
        gates = gates == null ? List.of() : List.copyOf(gates);
        currentEntryBlockers = currentEntryBlockers == null ? List.of() : List.copyOf(currentEntryBlockers);
        safeLiveBlockers = safeLiveBlockers == null ? List.of() : List.copyOf(safeLiveBlockers);
    }

    public record Gate(
            String id,
            GateState state,
            boolean enforcedOnCurrentPath,
            boolean blocksSafeLive,
            String detail
    ) {
    }

    public enum GateState {
        PASSING,
        BLOCKING,
        NOT_APPLICABLE,
        UNAVAILABLE
    }
}
