package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.trade.model.ExecutionMode;

import java.time.Instant;

public record OrderDispatchClaim(
        Long dispatchId,
        String clientOrderId,
        String leaseOwner,
        ExecutionMode executionMode,
        String payloadJson,
        Instant acceptedAt,
        Instant claimedAt
) {
}
