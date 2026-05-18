package com.vokerg.voktrader.outbox;

import com.vokerg.voktrader.trade.TradeSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "voktrader.outbox.processor", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxEventProcessor {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(60);

    private final OutboxEventRepository outboxEventRepository;
    private final TradeSettlementService tradeSettlementService;
    private final ObjectMapper objectMapper;

    @Scheduled(
            fixedDelayString = "${voktrader.outbox.processor.poll-ms:5000}",
            initialDelayString = "${voktrader.outbox.processor.initial-delay-ms:5000}"
    )
    public void processPending() {
        List<OutboxEventEntity> events = outboxEventRepository
                .findTop25ByTypeAndStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        OutboxEventType.MARKET_RESOLVED,
                        OutboxEventStatus.PENDING,
                        Instant.now()
                );
        events.forEach(this::processEvent);
    }

    public void processEvent(OutboxEventEntity event) {
        if (event == null || event.getStatus() != OutboxEventStatus.PENDING) {
            return;
        }
        try {
            event.markProcessing();
            outboxEventRepository.saveAndFlush(event);

            if (event.getType() == OutboxEventType.MARKET_RESOLVED) {
                MarketResolvedOutboxPayload payload = objectMapper.readValue(
                        event.getPayloadJson(),
                        MarketResolvedOutboxPayload.class
                );
                log.info(
                        "OUTBOX MARKET_RESOLVED processing id={} source={} mode={} marketId={} winningOutcome={}",
                        event.getId(),
                        payload.source(),
                        resolutionMode(payload.source()),
                        payload.marketId(),
                        payload.winningOutcome()
                );
                tradeSettlementService.settleOpenTradesForResolvedMarket(
                        payload.marketId(),
                        payload.winningOutcome()
                );
            }

            event.markProcessed(Instant.now());
            outboxEventRepository.save(event);
            log.info("OUTBOX event processed id={} type={} aggregateId={}",
                    event.getId(), event.getType(), event.getAggregateId());
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (event.getAttempts() + 1 >= MAX_ATTEMPTS) {
                event.markFailed(message);
            } else {
                event.markRetry(message, Instant.now().plus(RETRY_DELAY));
            }
            outboxEventRepository.save(event);
            log.warn("Outbox event processing failed id={} type={} attempts={}",
                    event.getId(), event.getType(), event.getAttempts(), ex);
        }
    }

    private String resolutionMode(String source) {
        if ("websocket".equalsIgnoreCase(source)) {
            return "NORMAL";
        }
        if ("gamma_poll".equalsIgnoreCase(source)) {
            return "EMERGENCY";
        }
        return "UNKNOWN";
    }
}
