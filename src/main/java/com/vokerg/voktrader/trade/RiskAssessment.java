package com.vokerg.voktrader.trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.vokerg.voktrader.trade.model.TradeRiskCheckEntity;

public class RiskAssessment {
    private final List<TradeRiskCheckEntity> checks = new ArrayList<>();

    public void add(TradeRiskCheckEntity check) {
        checks.add(check);
    }

    public List<TradeRiskCheckEntity> checks() {
        return Collections.unmodifiableList(checks);
    }

    public boolean passed() {
        return checks.stream().noneMatch(check -> !check.isPassed() && RiskSeverity.BLOCK.equals(check.getSeverity()));
    }

    public String firstBlockMessage() {
        return checks.stream()
                .filter(check -> !check.isPassed() && RiskSeverity.BLOCK.equals(check.getSeverity()))
                .map(TradeRiskCheckEntity::getMessage)
                .findFirst()
                .orElse(null);
    }
}
