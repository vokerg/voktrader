package com.vokerg.voktrader.trade.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "trade_risk_checks", indexes = {
        @Index(name = "idx_trade_risk_checks_trade", columnList = "trade_id"),
        @Index(name = "idx_trade_risk_checks_order", columnList = "trade_order_id"),
        @Index(name = "idx_trade_risk_checks_name", columnList = "check_name"),
        @Index(name = "idx_trade_risk_checks_correlation", columnList = "correlation_id")
})
public class TradeRiskCheckEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id")
    private Long tradeId;

    @Column(name = "trade_order_id")
    private Long tradeOrderId;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private ExecutionMode mode;

    @Column(name = "check_name", nullable = false)
    private String checkName;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private RiskSeverity severity;

    @Column(name = "observed_value", length = 500)
    private String observedValue;

    @Column(name = "limit_value", length = 500)
    private String limitValue;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TradeRiskCheckEntity() {
    }

    public static TradeRiskCheckEntity of(Long tradeId, Long orderId, ExecutionMode mode, String checkName, boolean passed, RiskSeverity severity, Object observedValue, Object limitValue, String message) {
        return of(tradeId, orderId, null, mode, checkName, passed, severity, observedValue, limitValue, message);
    }

    public static TradeRiskCheckEntity of(Long tradeId, Long orderId, String correlationId, ExecutionMode mode, String checkName, boolean passed, RiskSeverity severity, Object observedValue, Object limitValue, String message) {
        TradeRiskCheckEntity entity = new TradeRiskCheckEntity();
        entity.tradeId = tradeId;
        entity.tradeOrderId = orderId;
        entity.correlationId = correlationId;
        entity.mode = mode;
        entity.checkName = checkName;
        entity.passed = passed;
        entity.severity = severity;
        entity.observedValue = observedValue == null ? null : String.valueOf(observedValue);
        entity.limitValue = limitValue == null ? null : String.valueOf(limitValue);
        entity.message = message;
        entity.createdAt = Instant.now();
        return entity;
    }

    public Long getId() { return id; }
    public Long getTradeId() { return tradeId; }
    public Long getTradeOrderId() { return tradeOrderId; }
    public String getCorrelationId() { return correlationId; }
    public ExecutionMode getMode() { return mode; }
    public String getCheckName() { return checkName; }
    public boolean isPassed() { return passed; }
    public RiskSeverity getSeverity() { return severity; }
    public String getObservedValue() { return observedValue; }
    public String getLimitValue() { return limitValue; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
