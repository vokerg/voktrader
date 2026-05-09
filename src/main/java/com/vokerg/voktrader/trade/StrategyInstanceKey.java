package com.vokerg.voktrader.trade;

import java.util.Objects;

public record StrategyInstanceKey(
        Long botId,
        String strategyId,
        String configHash,
        String accountId
) {
    public StrategyInstanceKey {
        Objects.requireNonNull(strategyId, "strategyId is required");
    }

    public static StrategyInstanceKey of(Long botId, String strategyId) {
        return new StrategyInstanceKey(botId, strategyId, null, null);
    }

    public static StrategyInstanceKey of(Long botId, String strategyId, String configHash, String accountId) {
        return new StrategyInstanceKey(botId, strategyId, configHash, accountId);
    }
}
