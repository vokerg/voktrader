package com.vokerg.voktrader.api.telemetry;

import com.vokerg.voktrader.telemetry.TradingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class TradingEventStreamHub {
    private static final int MAX_REPLAY_EVENTS = 200;
    private static final int SINK_BUFFER_SIZE = 1024;

    private final Sinks.Many<TradingEvent> sink = Sinks.many()
            .multicast()
            .onBackpressureBuffer(SINK_BUFFER_SIZE, false);
    private final Deque<TradingEvent> replayBuffer = new ArrayDeque<>(MAX_REPLAY_EVENTS);

    @EventListener
    public void onTradingEvent(TradingEvent event) {
        if (event == null) {
            return;
        }

        appendToReplay(event);
        sink.tryEmitNext(event);
    }

    public Flux<TradingEvent> stream(
            String phase,
            String type,
            Long botId,
            String marketId,
            int replay
    ) {
        EventFilter filter = new EventFilter(phase, type, botId, marketId);
        int replayCount = Math.clamp(replay, 0, MAX_REPLAY_EVENTS);

        List<TradingEvent> replayEvents;
        synchronized (replayBuffer) {
            List<TradingEvent> matchingReplayEvents = replayBuffer.stream()
                    .filter(filter::matches)
                    .toList();
            replayEvents = matchingReplayEvents.stream()
                    .skip(Math.max(0, matchingReplayEvents.size() - replayCount))
                    .toList();
        }

        return Flux.concat(
                Flux.fromIterable(replayEvents),
                sink.asFlux().filter(filter::matches)
        );
    }

    private void appendToReplay(TradingEvent event) {
        synchronized (replayBuffer) {
            replayBuffer.addLast(event);
            while (replayBuffer.size() > MAX_REPLAY_EVENTS) {
                replayBuffer.removeFirst();
            }
        }
    }

    private record EventFilter(String phase, String type, Long botId, String marketId) {
        private EventFilter {
            phase = normalize(phase);
            type = normalize(type);
            marketId = blankToNull(marketId);
        }

        private boolean matches(TradingEvent event) {
            if (phase != null && !phase.equals(normalize(event.phase()))) {
                return false;
            }
            if (type != null) {
                String eventType = normalize(event.type());
                if (eventType == null || !(eventType.equals(type) || eventType.startsWith(type))) {
                    return false;
                }
            }
            if (botId != null && !Objects.equals(botId, event.botId())) {
                return false;
            }
            return marketId == null || marketId.equals(event.marketId());
        }

        private static String normalize(String value) {
            String normalized = blankToNull(value);
            return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
        }

        private static String blankToNull(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }
}
