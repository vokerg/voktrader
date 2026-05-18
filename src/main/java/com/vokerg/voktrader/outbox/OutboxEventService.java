package com.vokerg.voktrader.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OutboxEventService {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public OutboxEventEntity enqueueOnce(
            OutboxEventType type,
            String idempotencyKey,
            String aggregateType,
            String aggregateId,
            Object payload
    ) {
        return outboxEventRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> create(type, idempotencyKey, aggregateType, aggregateId, payload));
    }

    private OutboxEventEntity create(
            OutboxEventType type,
            String idempotencyKey,
            String aggregateType,
            String aggregateId,
            Object payload
    ) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            return outboxEventRepository.saveAndFlush(OutboxEventEntity.pending(
                    type,
                    idempotencyKey,
                    aggregateType,
                    aggregateId,
                    payloadJson,
                    Instant.now()
            ));
        } catch (DataIntegrityViolationException ex) {
            return outboxEventRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox event " + type + " key=" + idempotencyKey, ex);
        }
    }
}
