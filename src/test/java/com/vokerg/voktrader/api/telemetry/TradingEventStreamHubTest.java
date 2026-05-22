package com.vokerg.voktrader.api.telemetry;

import com.vokerg.voktrader.telemetry.TradingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventStreamHubTest {
    @Test
    void receivesPublishedTradingEvent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TradingEventStreamHub.class)) {
            TradingEventStreamHub hub = context.getBean(TradingEventStreamHub.class);
            TradingEvent event = event("PRICE_WS_UPDATE", "PRICE", 7L, "market-1");

            StepVerifier.create(hub.stream(null, null, null, null, 0).take(1))
                    .then(() -> context.publishEvent(event))
                    .expectNext(event)
                    .verifyComplete();
        }
    }

    @Test
    void replayReturnsRecentEvents() {
        TradingEventStreamHub hub = new TradingEventStreamHub();
        TradingEvent older = event("MARKET_REFRESH", "MARKET", 1L, "market-1");
        TradingEvent newer = event("PRICE_WS_UPDATE", "PRICE", 1L, "market-1");

        hub.onTradingEvent(older);
        hub.onTradingEvent(newer);

        StepVerifier.create(hub.stream(null, null, null, null, 1).take(1))
                .expectNext(newer)
                .verifyComplete();
    }

    @Test
    void filtersReplayByPhaseTypeBotAndMarket() {
        TradingEventStreamHub hub = new TradingEventStreamHub();
        TradingEvent matching = event("PAPER_ORDER_FILLED", "EXECUTION", 42L, "market-2");
        hub.onTradingEvent(event("MARKET_REFRESH", "MARKET", 42L, "market-2"));
        hub.onTradingEvent(event("PAPER_ORDER_RESTING", "EXECUTION", 41L, "market-2"));
        hub.onTradingEvent(event("PAPER_ORDER_CANCELLED", "EXECUTION", 42L, "market-1"));
        hub.onTradingEvent(matching);

        TradingEvent replayed = hub.stream("execution", "paper_order_", 42L, "market-2", 50)
                .take(1)
                .blockFirst();

        assertThat(replayed).isEqualTo(matching);
    }

    private static TradingEvent event(String type, String phase, Long botId, String marketId) {
        return new TradingEvent(
                Instant.parse("2026-05-22T10:15:30Z"),
                type,
                phase,
                "strategy-v2",
                "rule-a",
                botId,
                marketId,
                marketId + "-slug",
                "token-1",
                "YES",
                "test event",
                Map.of("price", "0.42")
        );
    }
}
