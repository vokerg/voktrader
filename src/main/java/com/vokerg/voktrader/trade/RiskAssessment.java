package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.RiskSeverity;
import com.vokerg.voktrader.trade.model.TradeRiskCheckEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structured result from the central entry-risk policy. */
public class RiskAssessment {
    private final String correlationId;
    private final List<TradeRiskCheckEntity> checks = new ArrayList<>();

    public RiskAssessment() {
        this(null);
    }

    public RiskAssessment(String correlationId) {
        this.correlationId = correlationId;
    }

    public String correlationId() {
        return correlationId;
    }

    public void add(TradeRiskCheckEntity check) {
        checks.add(check);
    }

    public List<TradeRiskCheckEntity> checks() {
        return Collections.unmodifiableList(checks);
    }

    public List<TradeRiskCheckEntity> blockingChecks() {
        return checksBySeverity(RiskSeverity.BLOCK);
    }

    public List<TradeRiskCheckEntity> warningChecks() {
        return checksBySeverity(RiskSeverity.WARN);
    }

    public List<TradeRiskCheckEntity> informationalChecks() {
        return checksBySeverity(RiskSeverity.INFO);
    }

    public boolean passed() {
        return blockingChecks().stream().noneMatch(check -> !check.isPassed());
    }

    public String firstBlockMessage() {
        return blockingChecks().stream()
                .filter(check -> !check.isPassed())
                .map(TradeRiskCheckEntity::getMessage)
                .findFirst()
                .orElse(null);
    }

    private List<TradeRiskCheckEntity> checksBySeverity(RiskSeverity severity) {
        return checks.stream().filter(check -> severity.equals(check.getSeverity())).toList();
    }
}
