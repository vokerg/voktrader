package com.vokerg.voktrader.trade.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "order_dispatch_outbox",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_order_dispatch_outbox_intent",
                        columnNames = "order_intent_id"
                ),
                @UniqueConstraint(
                        name = "uk_order_dispatch_outbox_client_order_id",
                        columnNames = "client_order_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_order_dispatch_outbox_ready",
                        columnList = "state,next_attempt_at,created_at"
                ),
                @Index(
                        name = "idx_order_dispatch_outbox_lease",
                        columnList = "state,lease_expires_at"
                )
        }
)
public class OrderDispatchOutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_intent_id", nullable = false, updatable = false)
    private OrderIntentEntity orderIntent;

    @Column(name = "client_order_id", nullable = false, length = 128, updatable = false)
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private OrderDispatchState state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "remote_order_id", length = 255)
    private String remoteOrderId;

    @Column(name = "unknown_outcome_at")
    private Instant unknownOutcomeAt;

    @Column(name = "unknown_outcome_reason", length = 255)
    private String unknownOutcomeReason;

    @Column(name = "unknown_outcome_details", columnDefinition = "TEXT")
    private String unknownOutcomeDetails;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OrderDispatchOutboxEntity() {
    }

    static OrderDispatchOutboxEntity ready(OrderIntentEntity orderIntent, Instant now) {
        OrderDispatchOutboxEntity entity = new OrderDispatchOutboxEntity();
        entity.orderIntent = Objects.requireNonNull(orderIntent, "orderIntent is required");
        entity.clientOrderId = orderIntent.getClientOrderId();
        entity.state = OrderDispatchState.OUTBOX_READY;
        entity.attempts = 0;
        entity.nextAttemptAt = Objects.requireNonNull(now, "now is required");
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public Long getId() {
        return id;
    }

    public OrderIntentEntity getOrderIntent() {
        return orderIntent;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public OrderDispatchState getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getRemoteOrderId() {
        return remoteOrderId;
    }

    public Instant getUnknownOutcomeAt() {
        return unknownOutcomeAt;
    }

    public String getUnknownOutcomeReason() {
        return unknownOutcomeReason;
    }

    public String getUnknownOutcomeDetails() {
        return unknownOutcomeDetails;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
