package com.vokerg.voktrader.strategy;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class StrategyConfig {

    public BigDecimal maxYesPrice() {
        return new BigDecimal("0.31");
    }

    public BigDecimal maxSpread() {
        return new BigDecimal("0.03");
    }
}
