package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.trade.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutorFillResponse(
        String remoteOrderId,
        String tradeId,
        String fillId,
        String tokenId,
        String marketId,
        TradeSide side,
        BigDecimal price,
        BigDecimal shares,
        BigDecimal fee,
        LiquidityRole role,
        Instant timestamp,
        String rawResponse
) {
}
