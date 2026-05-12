package com.vokerg.voktrader.api.bot.dto;

public record BotUpdateRequest(
        String marketFamily,
        String asset,
        String interval,
        String strategyId,
        String strategyConfigId,
        String subStrategyId,
        Boolean enabled
) {
}
