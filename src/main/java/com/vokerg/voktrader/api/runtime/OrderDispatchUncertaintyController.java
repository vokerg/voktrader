package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.trade.outbox.OrderDispatchOutboxEntity;
import com.vokerg.voktrader.trade.outbox.OrderDispatchOutboxRepository;
import com.vokerg.voktrader.trade.outbox.OrderDispatchState;
import com.vokerg.voktrader.trade.outbox.OrderDispatchStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/runtime/order-dispatch")
public class OrderDispatchUncertaintyController {
    private static final List<OrderDispatchState> UNRESOLVED = List.of(
            OrderDispatchState.UNKNOWN,
            OrderDispatchState.RECONCILE,
            OrderDispatchState.MANUAL_REVIEW
    );

    private final OrderDispatchOutboxRepository repository;
    private final OrderDispatchStateService stateService;

    public OrderDispatchUncertaintyController(
            OrderDispatchOutboxRepository repository,
            OrderDispatchStateService stateService
    ) {
        this.repository = repository;
        this.stateService = stateService;
    }

    @GetMapping("/unresolved")
    public List<UnresolvedDispatch> unresolved() {
        return repository.findTop100ByStateInOrderByUnknownOutcomeAtAsc(UNRESOLVED).stream()
                .map(UnresolvedDispatch::from)
                .toList();
    }

    @PostMapping("/{clientOrderId}/manual-review")
    public UnresolvedDispatch manualReview(
            @PathVariable String clientOrderId,
            @RequestBody ResolutionEvidence evidence
    ) {
        stateService.markManualReview(clientOrderId, evidence.requiredDetails(), Instant.now());
        return current(clientOrderId);
    }

    @PostMapping("/{clientOrderId}/resolve-accepted")
    public UnresolvedDispatch resolveAccepted(
            @PathVariable String clientOrderId,
            @RequestBody AcceptedResolution resolution
    ) {
        stateService.resolveAccepted(
                clientOrderId,
                resolution.requiredRemoteOrderId(),
                resolution.requiredDetails(),
                Instant.now()
        );
        return current(clientOrderId);
    }

    @PostMapping("/{clientOrderId}/resolve-rejected")
    public UnresolvedDispatch resolveRejected(
            @PathVariable String clientOrderId,
            @RequestBody ResolutionEvidence evidence
    ) {
        stateService.resolveRejected(clientOrderId, evidence.requiredDetails(), Instant.now());
        return current(clientOrderId);
    }

    private UnresolvedDispatch current(String clientOrderId) {
        return repository.findByClientOrderId(clientOrderId)
                .map(UnresolvedDispatch::from)
                .orElseThrow(() -> new IllegalStateException("Unknown clientOrderId " + clientOrderId));
    }

    public record ResolutionEvidence(String details) {
        String requiredDetails() {
            if (details == null || details.isBlank()) throw new IllegalArgumentException("details is required");
            return details;
        }
    }

    public record AcceptedResolution(String remoteOrderId, String details) {
        String requiredRemoteOrderId() {
            if (remoteOrderId == null || remoteOrderId.isBlank()) {
                throw new IllegalArgumentException("remoteOrderId is required");
            }
            return remoteOrderId;
        }

        String requiredDetails() {
            if (details == null || details.isBlank()) throw new IllegalArgumentException("details is required");
            return details;
        }
    }

    public record UnresolvedDispatch(
            String clientOrderId,
            String state,
            int attempts,
            Instant lastAttemptAt,
            Instant unknownOutcomeAt,
            String reason,
            String details,
            String remoteOrderId,
            String executorStatus,
            Instant updatedAt
    ) {
        static UnresolvedDispatch from(OrderDispatchOutboxEntity entity) {
            return new UnresolvedDispatch(
                    entity.getClientOrderId(), entity.getState().name(), entity.getAttempts(),
                    entity.getLastAttemptAt(), entity.getUnknownOutcomeAt(), entity.getUnknownOutcomeReason(),
                    entity.getUnknownOutcomeDetails(), entity.getRemoteOrderId(), entity.getExecutorStatus(),
                    entity.getUpdatedAt()
            );
        }
    }
}
