package com.vokerg.voktrader.marketdata;

import java.math.BigDecimal;

public record OrderBookLevel(
        BigDecimal price,
        BigDecimal size
) {
    public boolean usable() {
        return price != null
                && size != null
                && price.compareTo(BigDecimal.ZERO) > 0
                && size.compareTo(BigDecimal.ZERO) > 0;
    }
}
