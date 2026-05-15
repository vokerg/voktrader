package com.vokerg.voktrader.economy;

import java.math.BigDecimal;

import com.vokerg.voktrader.trade.model.TradeEntity;

public interface TradeEconomy {
    FeeEstimate estimateFee(BigDecimal shares, BigDecimal price, LiquidityRole liquidityRole);

    ExitEconomy estimateExit(
            TradeEntity trade,
            BigDecimal exitPrice,
            BigDecimal minimumProfitUsd,
            LiquidityRole liquidityRole
    );
}
