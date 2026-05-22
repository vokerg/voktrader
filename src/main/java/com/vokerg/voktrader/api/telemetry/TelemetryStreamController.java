package com.vokerg.voktrader.api.telemetry;

import com.vokerg.voktrader.telemetry.TradingEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryStreamController {
    private final TradingEventStreamHub hub;

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TradingEvent>> stream(
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long botId,
            @RequestParam(required = false) String marketId,
            @RequestParam(defaultValue = "50") int replay
    ) {
        Flux<ServerSentEvent<TradingEvent>> events = hub.stream(phase, type, botId, marketId, replay)
                .map(event -> ServerSentEvent.builder(event)
                        .event("trading-event")
                        .id(event.timestamp() + ":" + event.type())
                        .build());

        Flux<ServerSentEvent<TradingEvent>> keepAlive = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<TradingEvent>builder()
                        .event("ping")
                        .comment("keepalive")
                        .build());

        return Flux.merge(events, keepAlive);
    }
}
