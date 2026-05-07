package com.vokerg.voktrader.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BacktestRequest(
        @NotBlank String strategyId,
        @NotEmpty List<String> marketIds,
        Long botId
) {
}
