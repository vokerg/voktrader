package com.vokerg.voktrader.bot.dto;

import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotStatus;
import com.vokerg.voktrader.bot.MarketFamily;

import java.time.Instant;

public record BotConfigView(
        Long id,
        String name,
        boolean enabled,
        MarketFamily marketFamily,
        String asset,
        String interval,
        String strategyId,
        BotStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static BotConfigView from(BotConfigEntity entity) {
        return new BotConfigView(
                entity.getId(),
                entity.getName(),
                entity.isEnabled(),
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
