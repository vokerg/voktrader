package com.vokerg.voktrader.bot.dto;

public record BotSwitchRequest(
        String marketFamily,
        String asset,
        String interval,
        String strategyId,
        Boolean enabled
) {
}
