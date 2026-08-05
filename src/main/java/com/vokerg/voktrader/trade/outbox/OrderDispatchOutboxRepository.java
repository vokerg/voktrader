package com.vokerg.voktrader.trade.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderDispatchOutboxRepository extends JpaRepository<OrderDispatchOutboxEntity, Long> {
    Optional<OrderDispatchOutboxEntity> findByClientOrderId(String clientOrderId);

    long countByState(OrderDispatchState state);

    boolean existsByStateIn(Collection<OrderDispatchState> states);

    List<OrderDispatchOutboxEntity> findTop100ByStateInOrderByUnknownOutcomeAtAsc(
            Collection<OrderDispatchState> states
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OrderDispatchOutboxEntity d
            set d.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.UNKNOWN,
                d.unknownOutcomeAt = :now,
                d.unknownOutcomeReason = :reason,
                d.unknownOutcomeDetails = :details,
                d.lastError = :lastError,
                d.submitRttMs = :submitRttMs,
                d.leaseOwner = null,
                d.leaseExpiresAt = null,
                d.updatedAt = :now
            where d.id = :id
              and d.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.SUBMITTING
              and d.leaseOwner = :owner
            """)
    int markUnknown(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("reason") String reason,
            @Param("details") String details,
            @Param("lastError") String lastError,
            @Param("submitRttMs") long submitRttMs,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OrderDispatchOutboxEntity d
            set d.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.MANUAL_REVIEW,
                d.unknownOutcomeReason = 'REMOTE_TRUTH_NOT_UNIQUE',
                d.unknownOutcomeDetails = :details,
                d.lastError = :details,
                d.updatedAt = :now
            where d.clientOrderId = :clientOrderId
              and d.state in (
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.UNKNOWN,
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.RECONCILE,
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.MANUAL_REVIEW
              )
            """)
    int markManualReview(
            @Param("clientOrderId") String clientOrderId,
            @Param("details") String details,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OrderDispatchOutboxEntity d
            set d.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.SUBMITTED,
                d.remoteOrderId = :remoteOrderId,
                d.executorStatus = 'RECONCILED_ACCEPTED',
                d.executorResponse = :details,
                d.submittedAt = :now,
                d.lastError = null,
                d.updatedAt = :now
            where d.clientOrderId = :clientOrderId
              and d.state in (
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.UNKNOWN,
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.RECONCILE,
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.MANUAL_REVIEW
              )
            """)
    int resolveAccepted(
            @Param("clientOrderId") String clientOrderId,
            @Param("remoteOrderId") String remoteOrderId,
            @Param("details") String details,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OrderDispatchOutboxEntity d
            set d.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.FAILED,
                d.executorStatus = 'RECONCILED_REJECTED',
                d.executorResponse = :details,
                d.completedAt = :now,
                d.lastError = :details,
                d.updatedAt = :now
            where d.clientOrderId = :clientOrderId
              and d.state in (
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.UNKNOWN,
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.RECONCILE,
                com.vokerg.voktrader.trade.outbox.OrderDispatchState.MANUAL_REVIEW
              )
            """)
    int resolveRejected(
            @Param("clientOrderId") String clientOrderId,
            @Param("details") String details,
            @Param("now") Instant now
    );

    @Query(value = """
            SELECT *
            FROM order_dispatch_outbox
            WHERE state = 'OUTBOX_READY'
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OrderDispatchOutboxEntity> lockReadyForClaim(@Param("now") Instant now, Pageable pageable);

    @Query(value = """
            SELECT *
            FROM order_dispatch_outbox
            WHERE state = 'SUBMITTING'
              AND lease_expires_at <= :now
            ORDER BY lease_expires_at, id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OrderDispatchOutboxEntity> lockExpiredLeases(@Param("now") Instant now, Pageable pageable);
}
