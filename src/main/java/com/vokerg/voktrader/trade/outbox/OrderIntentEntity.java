package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "order_intents",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_order_intents_client_order_id",
                        columnNames = "client_order_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_order_intents_risk_decision",
                        columnList = "risk_decision_id,accepted_at"
                ),
                @Index(name = "idx_order_intents_hash", columnList = "intent_hash")
        }
)
public class OrderIntentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_order_id", nullable = false, length = 128, updatable = false)
    private String clientOrderId;

    @Column(name = "intent_hash", nullable = false, length = 64, updatable = false)
    private String intentHash;

    @Column(name = "risk_decision_id", nullable = false, length = 255, updatable = false)
    private String riskDecisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 16, updatable = false)
    private ExecutionMode executionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16, updatable = false)
    private TradeSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private OrderIntentState state;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OrderIntentEntity() {
    }

    static OrderIntentEntity accepted(
            String clientOrderId,
            String intentHash,
            String riskDecisionId,
            ExecutionMode executionMode,
            TradeSide side,
            String payloadJson,
            Instant acceptedAt
    ) {
        OrderIntentEntity entity = new OrderIntentEntity();
        entity.clientOrderId = requireText(clientOrderId, "clientOrderId");
        entity.intentHash = requireText(intentHash, "intentHash");
        entity.riskDecisionId = requireText(riskDecisionId, "riskDecisionId");
        entity.executionMode = Objects.requireNonNull(executionMode, "executionMode is required");
        entity.side = Objects.requireNonNull(side, "side is required");
        entity.state = OrderIntentState.ACCEPTED;
        entity.payloadJson = requireText(payloadJson, "payloadJson");
        entity.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt is required");
        return entity;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getIntentHash() {
        return intentHash;
    }

    public String getRiskDecisionId() {
        return riskDecisionId;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public TradeSide getSide() {
        return side;
    }

    public OrderIntentState getState() {
        return state;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public long getVersion() {
        return version;
    }
}
