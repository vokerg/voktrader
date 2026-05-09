package com.vokerg.voktrader.backtest;

public enum BacktestFillModel {
    IMMEDIATE_TAKER,
    MAKER_NO_FILL,
    MAKER_TOUCH,
    MAKER_CROSS_PESSIMISTIC;

    public static BacktestFillModel parse(String value) {
        if (value == null || value.isBlank()) {
            return MAKER_CROSS_PESSIMISTIC;
        }
        return BacktestFillModel.valueOf(value.trim().replace("-", "_").toUpperCase());
    }
}
