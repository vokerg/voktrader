package com.vokerg.voktrader.telemetry;

import java.time.Instant;
import java.util.Map;

public record TradingEvent(
        Instant timestamp,
        String type,
        String phase,
        String strategyId,
        String ruleId,
        Long botId,
        String marketId,
        String marketSlug,
        String tokenId,
        String outcome,
        String reason,
        Map<String, Object> data
) {
}
