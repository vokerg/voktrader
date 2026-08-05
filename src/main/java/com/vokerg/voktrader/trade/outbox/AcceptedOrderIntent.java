package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;

import java.time.Instant;

public record AcceptedOrderIntent(
        Long intentId,
        Long dispatchId,
        String clientOrderId,
        String intentHash,
        String riskDecisionId,
        ExecutionMode mode,
        TradeSide side,
        OrderIntentState intentState,
        OrderDispatchState dispatchState,
        Instant acceptedAt
) {
}
