package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.paper.Side;
import java.math.BigDecimal;

public record SignalDecision(
        boolean shouldBuy,
        Side side,
        BigDecimal price,
        String reason
) {

    public static SignalDecision noSignal(String reason) {
        return new SignalDecision(false, null, null, reason);
    }

    public static SignalDecision buy(Side side, BigDecimal price, String reason) {
        return new SignalDecision(true, side, price, reason);
    }
}
