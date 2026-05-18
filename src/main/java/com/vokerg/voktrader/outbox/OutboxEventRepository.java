package com.vokerg.voktrader.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {
    Optional<OutboxEventEntity> findByIdempotencyKey(String idempotencyKey);

    List<OutboxEventEntity> findTop25ByTypeAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OutboxEventType type,
            OutboxEventStatus status,
            Instant nextAttemptAt
    );
}
