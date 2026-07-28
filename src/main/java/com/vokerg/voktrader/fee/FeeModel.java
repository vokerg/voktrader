package com.vokerg.voktrader.fee;

import java.math.BigDecimal;

public interface FeeModel {
    String version();

    BigDecimal calculate(FeeMetadata metadata, FeeLiquidityRole role, BigDecimal price, BigDecimal shares);
}
