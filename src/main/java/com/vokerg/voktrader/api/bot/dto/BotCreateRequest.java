package com.vokerg.voktrader.api.bot.dto;

public record BotCreateRequest(
        String name,
        String marketFamily,
        String asset,
        String interval,
        String strategyId,
        Boolean enabled
) {
}
