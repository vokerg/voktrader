package com.vokerg.voktrader.outbox;

import java.time.Instant;

public record MarketResolvedOutboxPayload(
        String marketId,
        String winningAssetId,
        String winningOutcome,
        String source,
        Instant resolvedAt
) {
}
