package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "order_dispatch_outbox")
public class OrderDispatchOutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_intent_id", nullable = false, unique = true)
    private OrderIntentEntity orderIntent;

    @Column(name = "client_order_id", nullable = false, unique = true, length = 128)
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderDispatchState state;

    @Column(nullable = false)
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

    @Column(name = "remote_order_id")
    private String remoteOrderId;

    @Column(name = "executor_status", length = 128)
    private String executorStatus;

    @Column(name = "executor_response", columnDefinition = "TEXT")
    private String executorResponse;

    @Column(name = "queue_latency_ms")
    private Long queueLatencyMs;

    @Column(name = "submit_rtt_ms")
    private Long submitRttMs;

    @Column(name = "unknown_outcome_at")
    private Instant unknownOutcomeAt;

    @Column(name = "unknown_outcome_reason")
    private String unknownOutcomeReason;

    @Column(name = "unknown_outcome_details", columnDefinition = "TEXT")
    private String unknownOutcomeDetails;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrderDispatchOutboxEntity() {
    }

    private OrderDispatchOutboxEntity(
            OrderIntentEntity orderIntent,
            String clientOrderId,
            OrderDispatchState state,
            Instant nextAttemptAt,
            Instant createdAt
    ) {
        this.orderIntent = orderIntent;
        this.clientOrderId = clientOrderId;
        this.state = state;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    static OrderDispatchOutboxEntity ready(OrderIntentEntity orderIntent, Instant now) {
        return new OrderDispatchOutboxEntity(
                Objects.requireNonNull(orderIntent, "orderIntent is required"),
                orderIntent.getClientOrderId(),
                OrderDispatchState.OUTBOX_READY,
                now,
                now
        );
    }

    void claim(String owner, Instant claimedAt, Instant leaseUntil, long queueLatencyMs) {
        if (state != OrderDispatchState.OUTBOX_READY) {
            throw new IllegalStateException("Only OUTBOX_READY dispatches can be claimed");
        }
        if (nextAttemptAt.isAfter(claimedAt)) {
            throw new IllegalStateException("Dispatch is not ready for another attempt");
        }
        state = OrderDispatchState.SUBMITTING;
        attempts++;
        leaseOwner = requireText(owner, "owner");
        leaseExpiresAt = Objects.requireNonNull(leaseUntil, "leaseUntil is required");
        lastAttemptAt = claimedAt;
        this.queueLatencyMs = Math.max(0L, queueLatencyMs);
        updatedAt = claimedAt;
        lastError = null;
    }

    void markSubmitted(
            String owner,
            ExecutorOrderResponse response,
            Instant now,
            long submitRttMs
    ) {
        requireLease(owner);
        state = OrderDispatchState.SUBMITTED;
        submittedAt = now;
        remoteOrderId = response.exchangeOrderId();
        executorStatus = response.status();
        executorResponse = response.rawResponse();
        this.submitRttMs = Math.max(0L, submitRttMs);
        lastError = null;
        clearLease();
        updatedAt = now;
    }

    void markRejected(
            String owner,
            ExecutorOrderResponse response,
            Instant now,
            long submitRttMs
    ) {
        requireLease(owner);
        state = OrderDispatchState.FAILED;
        completedAt = now;
        remoteOrderId = response.exchangeOrderId();
        executorStatus = response.status();
        executorResponse = response.rawResponse();
        this.submitRttMs = Math.max(0L, submitRttMs);
        lastError = truncate(response.safeMessage(), 2000);
        clearLease();
        updatedAt = now;
    }

    void markReconcile(String owner, String reason, String details, Instant now) {
        requireLease(owner);
        enterReconcile(reason, details, now);
    }

    void markExpiredLeaseForReconcile(String reason, String details, Instant now) {
        if (!leaseExpiredAt(now)) {
            throw new IllegalStateException("Dispatch lease has not expired");
        }
        enterReconcile(reason, details, now);
    }

    private void enterReconcile(String reason, String details, Instant now) {
        state = OrderDispatchState.RECONCILE;
        unknownOutcomeAt = now;
        unknownOutcomeReason = truncate(requireText(reason, "reason"), 255);
        unknownOutcomeDetails = details;
        lastError = truncate(details, 2000);
        clearLease();
        updatedAt = now;
    }

    boolean leaseExpiredAt(Instant now) {
        return state == OrderDispatchState.SUBMITTING
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
    }

    private void requireLease(String owner) {
        if (state != OrderDispatchState.SUBMITTING) {
            throw new IllegalStateException("Dispatch is not SUBMITTING");
        }
        if (!Objects.equals(leaseOwner, owner)) {
            throw new IllegalStateException("Dispatch lease is owned by another worker");
        }
    }

    private void clearLease() {
        leaseOwner = null;
        leaseExpiresAt = null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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

    public String getExecutorStatus() {
        return executorStatus;
    }

    public String getExecutorResponse() {
        return executorResponse;
    }

    public Long getQueueLatencyMs() {
        return queueLatencyMs;
    }

    public Long getSubmitRttMs() {
        return submitRttMs;
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
