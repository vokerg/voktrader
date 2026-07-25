package com.vokerg.voktrader.polymarket.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vokerg.voktrader.config.PolymarketProperties;
import com.vokerg.voktrader.marketdata.TickSizeService;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketWebSocketClient {

    private final PolymarketProperties properties;
    private final ObjectMapper objectMapper;
    private final TickSizeService tickSizeService;

    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    @Value("${voktrader.market-data.websocket.ping-interval-ms:10000}")
    private long pingIntervalMs;

    public Disposable subscribeToMarketData(
            List<String> assetIds,
            Consumer<MarketWsMessageDto> onMessage
    ) {
        MarketWebSocketObserver observer = onMessage instanceof MarketWebSocketObserver candidate
                ? candidate
                : null;
        return Mono.defer(() -> connect(assetIds, onMessage, observer)
                        .then(Mono.<Void>error(new IllegalStateException("Polymarket WS connection completed"))))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.warn(
                                "Polymarket WS reconnect attempt={} error={}",
                                signal.totalRetries() + 1,
                                signal.failure().toString()
                        )))
                .subscribe(
                        null,
                        e -> log.error("Polymarket WS permanently failed", e),
                        () -> log.warn("Polymarket WS completed")
                );
    }

    public Mono<Void> connect(
            List<String> assetIds,
            Consumer<MarketWsMessageDto> onMessage
    ) {
        MarketWebSocketObserver observer = onMessage instanceof MarketWebSocketObserver candidate
                ? candidate
                : null;
        return connect(assetIds, onMessage, observer);
    }

    private Mono<Void> connect(
            List<String> assetIds,
            Consumer<MarketWsMessageDto> onMessage,
            MarketWebSocketObserver observer
    ) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Mono.error(new IllegalArgumentException("assetIds must not be empty"));
        }
        long effectivePingIntervalMs = pingIntervalMs > 0 ? pingIntervalMs : 10_000L;

        URI uri = URI.create(properties.marketWsUrl());

        return webSocketClient.execute(uri, session -> {
            MarketSubscription subscription = new MarketSubscription(
                    assetIds,
                    "market",
                    true
            );

            String subscriptionJson;
            try {
                subscriptionJson = objectMapper.writeValueAsString(subscription);
            } catch (Exception e) {
                return Mono.error(e);
            }

            log.info("Connecting Polymarket market WS with {} assetIds", assetIds.size());
            if (observer != null) {
                observer.onConnected();
            }

            Flux<WebSocketMessage> outbound = Flux.concat(
                    Mono.just(session.textMessage(subscriptionJson)),
                    Flux.interval(Duration.ofMillis(effectivePingIntervalMs))
                            .map(ignored -> session.textMessage("PING"))
            );
            Mono<Void> sendSubscriptionAndHeartbeats = session.send(outbound);

            Mono<Void> receiveMessages = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(payload -> handlePayload(payload, onMessage, observer))
                    .doOnError(e -> log.error("Polymarket WS receive error", e))
                    .then();

            return Mono.firstWithSignal(sendSubscriptionAndHeartbeats, receiveMessages).then();
        }).doOnError(error -> notifyDisconnected(observer, error))
                .doOnSuccess(ignored -> notifyDisconnected(observer, null));
    }

    private void handlePayload(
            String payload,
            Consumer<MarketWsMessageDto> onMessage,
            MarketWebSocketObserver observer
    ) {
        if (observer != null) {
            observer.onHeartbeat(Instant.now());
        }
        if (payload == null || payload.isBlank() || "PONG".equalsIgnoreCase(payload.trim())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);

            if (root.isArray()) {
                for (JsonNode node : root) {
                    handleSingleMessage(node, onMessage);
                }
                return;
            }

            handleSingleMessage(root, onMessage);
        } catch (Exception e) {
            log.warn("Could not parse WS payload: {}", payload, e);
        }
    }

    private void handleSingleMessage(
            JsonNode node,
            Consumer<MarketWsMessageDto> onMessage
    ) {
        try {
            if (!node.has("event_type")) {
                log.debug("Ignoring WS message without event_type: {}", node);
                return;
            }

            MarketWsMessageDto message = objectMapper.treeToValue(node, MarketWsMessageDto.class);

            log.debug(
                    "WS event={} market={} assetId={}",
                    message.eventType(),
                    message.market(),
                    message.assetId()
            );

            if (message.isTickSizeChange()) {
                tickSizeService.recordTickSizeChange(
                        message.assetId(),
                        message.market(),
                        message.oldTickSize(),
                        message.newTickSize(),
                        parseTimestamp(message.timestamp())
                );
            }
            onMessage.accept(message);
        } catch (Exception e) {
            log.warn("Could not map WS message: {}", node, e);
        }
    }

    private Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            long raw = Long.parseLong(value);
            return value.length() <= 10 ? Instant.ofEpochSecond(raw) : Instant.ofEpochMilli(raw);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException invalidTimestamp) {
                return Instant.now();
            }
        }
    }

    private void notifyDisconnected(MarketWebSocketObserver observer, Throwable cause) {
        if (observer != null) {
            observer.onDisconnected(cause);
        }
    }

    private record MarketSubscription(
            @JsonProperty("assets_ids")
            List<String> assetIds,

            String type,

            @JsonProperty("custom_feature_enabled")
            boolean customFeatureEnabled
    ) {
    }
}
