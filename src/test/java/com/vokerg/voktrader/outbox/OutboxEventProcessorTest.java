package com.vokerg.voktrader.outbox;

import com.vokerg.voktrader.trade.TradeSettlementService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventProcessorTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final TradeSettlementService tradeSettlementService = mock(TradeSettlementService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxEventProcessor processor = new OutboxEventProcessor(
            repository,
            tradeSettlementService,
            objectMapper
    );

    @Test
    void marketResolvedEventSettlesOpenTradesAndMarksProcessed() throws Exception {
        OutboxEventEntity event = marketResolvedEvent();

        processor.processEvent(event);

        verify(tradeSettlementService).settleOpenTradesForResolvedMarket("market-1", "Yes");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void failedSettlementSchedulesRetry() throws Exception {
        OutboxEventEntity event = marketResolvedEvent();
        org.mockito.Mockito.doThrow(new IllegalStateException("database down"))
                .when(tradeSettlementService)
                .settleOpenTradesForResolvedMarket("market-1", "Yes");

        processor.processEvent(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("database down");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        verify(repository).save(event);
    }

    private OutboxEventEntity marketResolvedEvent() throws Exception {
        String payloadJson = objectMapper.writeValueAsString(new MarketResolvedOutboxPayload(
                "market-1",
                "asset-yes",
                "Yes",
                "websocket",
                Instant.parse("2026-05-18T12:00:00Z")
        ));
        OutboxEventEntity event = OutboxEventEntity.pending(
                OutboxEventType.MARKET_RESOLVED,
                "MARKET_RESOLVED:market-1",
                "MARKET",
                "market-1",
                payloadJson,
                Instant.now()
        );
        when(repository.saveAndFlush(event)).thenReturn(event);
        when(repository.save(event)).thenReturn(event);
        return event;
    }
}
