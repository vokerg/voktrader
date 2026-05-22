package com.vokerg.voktrader.api.telemetry;

import com.vokerg.voktrader.telemetry.TradingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryStreamControllerTest {
    @Test
    void streamEndpointProducesTextEventStream() throws NoSuchMethodException {
        Method method = TelemetryStreamController.class.getMethod(
                "stream",
                String.class,
                String.class,
                Long.class,
                String.class,
                int.class
        );

        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertThat(mapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void streamReturnsTradingEventsAsServerSentEvents() {
        TradingEventStreamHub hub = new TradingEventStreamHub();
        TelemetryStreamController controller = new TelemetryStreamController(hub);
        TradingEvent event = new TradingEvent(
                Instant.parse("2026-05-22T10:15:30Z"),
                "TRADE_ROUTED",
                "ENTRY",
                "strategy-v2",
                "rule-a",
                10L,
                "market-1",
                "market-one",
                "token-1",
                "YES",
                "test route",
                Map.of("amountUsd", "1.00")
        );
        hub.onTradingEvent(event);

        StepVerifier.create(controller.stream(null, null, null, null, 1).take(1))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("trading-event");
                    assertThat(sse.data()).isEqualTo(event);
                    assertThat(sse.id()).isEqualTo("2026-05-22T10:15:30Z:TRADE_ROUTED");
                })
                .verifyComplete();
    }
}
