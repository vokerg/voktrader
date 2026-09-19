package com.vokerg.voktrader.polymarket.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class PolymarketUserWebSocketClient {
    private final UserWebSocketProperties properties;
    private final ObjectMapper objectMapper;
    private final UserWebSocketEventService eventService;
    private final UserWebSocketHealthService healthService;
    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    private Disposable subscription;

    public PolymarketUserWebSocketClient(
            UserWebSocketProperties properties,
            ObjectMapper objectMapper,
            UserWebSocketEventService eventService,
            UserWebSocketHealthService healthService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.healthService = healthService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.isEnabled()) {
            healthService.markDisabled();
            return;
        }
        if (!properties.credentialsConfigured()) {
            healthService.markConfigurationBlocked("authenticated user websocket credentials are not fully configured");
            log.warn("Authenticated user WebSocket is enabled but L2 credentials are incomplete");
            return;
        }
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }

        subscription = Mono.defer(this::connect)
                .then(Mono.<Void>error(new IllegalStateException("authenticated user WebSocket connection completed")))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> {
                            healthService.markConnecting();
                            log.warn(
                                    "Authenticated user WebSocket reconnect attempt={} error={}",
                                    signal.totalRetries() + 1,
                                    safeError(signal.failure())
                            );
                        }))
                .subscribe(
                        null,
                        error -> {
                            healthService.onDisconnected(Instant.now(), error);
                            log.error("Authenticated user WebSocket permanently failed: {}", safeError(error));
                        },
                        () -> log.warn("Authenticated user WebSocket completed")
                );
    }

    Mono<Void> connect() {
        healthService.markConnecting();
        URI uri = URI.create(properties.getUrl());
        return webSocketClient.execute(uri, session -> {
            UserSubscription userSubscription = new UserSubscription(
                    new UserAuth(
                            properties.getApiKey(),
                            properties.getApiSecret(),
                            properties.getApiPassphrase()
                    ),
                    "user"
            );

            String subscriptionJson;
            try {
                subscriptionJson = objectMapper.writeValueAsString(userSubscription);
            } catch (Exception exception) {
                return Mono.error(exception);
            }

            long generation = healthService.onConnected(Instant.now());
            log.info("Authenticated user WebSocket connected generation={}", generation);

            Flux<WebSocketMessage> outbound = Flux.concat(
                    Mono.just(session.textMessage(subscriptionJson)),
                    Flux.interval(properties.effectivePingInterval())
                            .map(ignored -> session.textMessage("PING"))
            );

            Mono<Void> sender = session.send(outbound);
            Mono<Void> receiver = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(ignored -> healthService.onHeartbeat(Instant.now()))
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(payload -> handlePayload(payload, generation))
                    .doOnError(error -> log.warn(
                            "Authenticated user WebSocket receive error: {}",
                            safeError(error)
                    ))
                    .then();

            return Mono.firstWithSignal(sender, receiver).then();
        }).doOnError(error -> healthService.onDisconnected(Instant.now(), error))
                .doOnSuccess(ignored -> healthService.onDisconnected(Instant.now(), null));
    }

    private void handlePayload(String payload, long generation) {
        if (payload == null || payload.isBlank() || "PONG".equalsIgnoreCase(payload.trim())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    handleSingle(node, generation);
                }
            } else {
                handleSingle(root, generation);
            }
        } catch (Exception exception) {
            log.warn("Ignoring malformed authenticated user WebSocket payload: {}", safeError(exception));
        }
    }

    private void handleSingle(JsonNode node, long generation) {
        if (node == null || !node.has("event_type")) {
            return;
        }
        try {
            UserWebSocketMessage message = objectMapper.treeToValue(node, UserWebSocketMessage.class);
            if (!message.isOrder() && !message.isTrade()) {
                return;
            }
            Instant receivedAt = Instant.now();
            healthService.onEvent(receivedAt);
            eventService.process(message, node.toString(), generation, receivedAt);
        } catch (Exception exception) {
            log.warn("Could not process authenticated user WebSocket event: {}", safeError(exception));
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
        healthService.stop();
    }

    private String safeError(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private record UserSubscription(
            UserAuth auth,
            String type
    ) {
    }

    private record UserAuth(
            @JsonProperty("apiKey") String apiKey,
            String secret,
            String passphrase
    ) {
    }
}
