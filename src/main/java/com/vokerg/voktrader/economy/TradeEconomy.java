package com.vokerg.voktrader.economy;

import com.vokerg.voktrader.trade.TradeEntity;

import java.math.BigDecimal;

public interface TradeEconomy {
    FeeEstimate estimateFee(BigDecimal shares, BigDecimal price, LiquidityRole liquidityRole);

    ExitEconomy estimateExit(
            TradeEntity trade,
            BigDecimal exitPrice,
            BigDecimal minimumProfitUsd,
            LiquidityRole liquidityRole
    );
}
