package com.vokerg.voktrader.outbox;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventServiceTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxEventService service = new OutboxEventService(repository, new ObjectMapper());

    @Test
    void enqueueOnceCreatesOnlyOneEventForSameIdempotencyKey() {
        AtomicReference<OutboxEventEntity> saved = new AtomicReference<>();
        when(repository.findByIdempotencyKey("MARKET_RESOLVED:market-1"))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.saveAndFlush(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> {
                    OutboxEventEntity entity = invocation.getArgument(0);
                    saved.set(entity);
                    return entity;
                });

        MarketResolvedOutboxPayload payload = new MarketResolvedOutboxPayload(
                "market-1",
                "asset-yes",
                "Yes",
                "websocket",
                Instant.parse("2026-05-18T12:00:00Z")
        );

        OutboxEventEntity first = service.enqueueOnce(
                OutboxEventType.MARKET_RESOLVED,
                "MARKET_RESOLVED:market-1",
                "MARKET",
                "market-1",
                payload
        );
        OutboxEventEntity second = service.enqueueOnce(
                OutboxEventType.MARKET_RESOLVED,
                "MARKET_RESOLVED:market-1",
                "MARKET",
                "market-1",
                payload
        );

        assertThat(second).isSameAs(first);
        assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(first.getPayloadJson()).contains("\"marketId\":\"market-1\"");
        verify(repository, times(1)).saveAndFlush(any(OutboxEventEntity.class));
    }
}
