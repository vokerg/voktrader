package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.economy.LiquidityRole;

public enum TradeOrderType {
    SIMULATED,
    FOK,
    FAK,
    GTC,
    GTD;

    public boolean expectsImmediateFill() {
        return this == FOK || this == FAK;
    }

    public boolean canRestOnBook() {
        return this == GTC || this == GTD;
    }

    public boolean prefersMaker() {
        return canRestOnBook();
    }

    public LiquidityRole expectedLiquidityRole() {
        return prefersMaker() ? LiquidityRole.MAKER : LiquidityRole.TAKER;
    }
}
