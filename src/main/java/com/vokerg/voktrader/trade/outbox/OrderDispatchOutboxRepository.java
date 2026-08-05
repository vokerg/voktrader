package com.vokerg.voktrader.trade.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderDispatchOutboxRepository extends JpaRepository<OrderDispatchOutboxEntity, Long> {
    Optional<OrderDispatchOutboxEntity> findByClientOrderId(String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select dispatch
            from OrderDispatchOutboxEntity dispatch
            join fetch dispatch.orderIntent
            where dispatch.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.OUTBOX_READY
              and dispatch.nextAttemptAt <= :now
            order by dispatch.createdAt, dispatch.id
            """)
    List<OrderDispatchOutboxEntity> lockReadyForClaim(
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select dispatch
            from OrderDispatchOutboxEntity dispatch
            join fetch dispatch.orderIntent
            where dispatch.state = com.vokerg.voktrader.trade.outbox.OrderDispatchState.SUBMITTING
              and dispatch.leaseExpiresAt <= :now
            order by dispatch.leaseExpiresAt, dispatch.id
            """)
    List<OrderDispatchOutboxEntity> lockExpiredLeases(
            @Param("now") Instant now,
            Pageable pageable
    );
}
