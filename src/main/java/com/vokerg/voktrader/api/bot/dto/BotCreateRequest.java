package com.vokerg.voktrader.api.bot.dto;

public record BotCreateRequest(
        String name,
        String marketFamily,
        String asset,
        String interval,
        String strategyId,
        String strategySetId,
        String subStrategyId,
        Boolean enabled
) {
}

