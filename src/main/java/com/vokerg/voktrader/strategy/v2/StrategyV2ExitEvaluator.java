package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.trade.StrategyRuntimeState;
import org.springframework.stereotype.Component;

@Component
public class StrategyV2ExitEvaluator {
    public void evaluate(StrategyV2Properties.Strategy strategy) {
        evaluate(strategy, null);
    }

    public void evaluate(StrategyV2Properties.Strategy strategy, StrategyRuntimeState runtimeState) {
        // Exit V2 is intentionally schema-first in this patch. Existing hardcoded strategies still own exits.
        // Configured exit rules are validated so the engine can gain execution support without schema churn.
    }
}
