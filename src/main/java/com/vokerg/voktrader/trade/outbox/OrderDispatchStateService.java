package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class OrderDispatchStateService {
    private static final String EXPIRED_LEASE_REASON = "LEASE_EXPIRED_AFTER_SUBMITTING";
    private static final String EXPIRED_LEASE_DETAILS =
            "The worker lease expired after SUBMITTING was committed. Reconcile remote truth before any retry.";

    private final OrderDispatchOutboxRepository dispatchRepository;
    private final OrderOutboxProperties properties;

    public OrderDispatchStateService(
            OrderDispatchOutboxRepository dispatchRepository,
            OrderOutboxProperties properties
    ) {
        this.dispatchRepository = dispatchRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderDispatchClaim> claimNext(String workerId, Instant now) {
        requireText(workerId, "workerId");
        Objects.requireNonNull(now, "now is required");

        List<OrderDispatchOutboxEntity> ready = dispatchRepository.lockReadyForClaim(
                now,
                PageRequest.of(0, 1)
        );
        if (ready.isEmpty()) {
            return Optional.empty();
        }

        OrderDispatchOutboxEntity dispatch = ready.getFirst();
        OrderIntentEntity intent = dispatch.getOrderIntent();
        long queueLatencyMs = Math.max(0L, Duration.between(intent.getAcceptedAt(), now).toMillis());
        dispatch.claim(
                workerId,
                now,
                now.plus(properties.getLeaseDuration()),
                queueLatencyMs
        );
        return Optional.of(new OrderDispatchClaim(
                dispatch.getId(),
                dispatch.getClientOrderId(),
                workerId,
                intent.getExecutionMode(),
                intent.getPayloadJson(),
                intent.getAcceptedAt(),
                now
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileExpiredLeases(Instant now, int limit) {
        Objects.requireNonNull(now, "now is required");
        List<OrderDispatchOutboxEntity> expired = dispatchRepository.lockExpiredLeases(
                now,
                PageRequest.of(0, Math.max(1, limit))
        );
        for (OrderDispatchOutboxEntity dispatch : expired) {
            dispatch.markExpiredLeaseForReconcile(
                    EXPIRED_LEASE_REASON,
                    EXPIRED_LEASE_DETAILS,
                    now
            );
        }
        return expired.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResponse(
            OrderDispatchClaim claim,
            ExecutorOrderResponse response,
            Instant now,
            long submitRttMs
    ) {
        Objects.requireNonNull(claim, "claim is required");
        Objects.requireNonNull(response, "response is required");
        Objects.requireNonNull(now, "now is required");

        OrderDispatchOutboxEntity dispatch = dispatchRepository.findById(claim.dispatchId())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing claimed dispatch " + claim.dispatchId()
                ));
        if (response.accepted()) {
            dispatch.markSubmitted(claim.leaseOwner(), response, now, submitRttMs);
            dispatch.getOrderIntent().markDispatched();
        } else {
            dispatch.markRejected(claim.leaseOwner(), response, now, submitRttMs);
            dispatch.getOrderIntent().markRejected();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAmbiguousFailure(
            OrderDispatchClaim claim,
            RuntimeException failure,
            Instant now
    ) {
        Objects.requireNonNull(claim, "claim is required");
        Objects.requireNonNull(failure, "failure is required");
        Objects.requireNonNull(now, "now is required");

        OrderDispatchOutboxEntity dispatch = dispatchRepository.findById(claim.dispatchId())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing claimed dispatch " + claim.dispatchId()
                ));
        String details = failure.getClass().getSimpleName()
                + ": "
                + (failure.getMessage() == null ? "executor submission failed" : failure.getMessage());
        dispatch.markReconcile(
                claim.leaseOwner(),
                "EXECUTOR_SUBMISSION_OUTCOME_AMBIGUOUS",
                details,
                now
        );
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
