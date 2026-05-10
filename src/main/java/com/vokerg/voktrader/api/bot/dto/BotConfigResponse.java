package com.vokerg.voktrader.api.bot.dto;

import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotStatus;
import com.vokerg.voktrader.bot.MarketFamily;

import java.time.Instant;

public record BotConfigResponse(
        Long id,
        String name,
        boolean enabled,
        boolean runtimeActive,
        MarketFamily marketFamily,
        String asset,
        String interval,
        String strategyId,
        BotStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static BotConfigResponse from(BotConfigEntity entity, boolean runtimeActive) {
        return new BotConfigResponse(
                entity.getId(),
                entity.getName(),
                entity.isEnabled(),
                runtimeActive,
                entity.getMarketFamily(),
                entity.getMarketFamily().asset().name(),
                entity.getMarketFamily().intervalCode(),
                entity.getStrategyId(),
                entity.getStatus(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
