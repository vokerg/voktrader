package com.vokerg.voktrader.bot.dto;

public record BotCreateRequest(
        String name,
        String marketFamily,
        String asset,
        String interval,
        String strategyId,
        Boolean enabled
) {
}
