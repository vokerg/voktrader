package com.vokerg.voktrader.polymarket.user;

import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class UserWebSocketEventService {
    private final UserWebSocketEventRepository eventRepository;
    private final TradeOrderRepository tradeOrderRepository;

    public UserWebSocketEventService(
            UserWebSocketEventRepository eventRepository,
            TradeOrderRepository tradeOrderRepository
    ) {
        this.eventRepository = eventRepository;
        this.tradeOrderRepository = tradeOrderRepository;
    }

    @Transactional
    public ProcessingResult process(
            UserWebSocketMessage message,
            String rawPayload,
            long connectionGeneration,
            Instant receivedAt
    ) {
        if (message == null || (!message.isOrder() && !message.isTrade())) {
            return new ProcessingResult(false, false, 0, "unsupported user websocket event");
        }

        Instant effectiveReceivedAt = receivedAt == null ? Instant.now() : receivedAt;
        String dedupeKey = dedupeKey(message, rawPayload);
        if (eventRepository.existsByDedupeKey(dedupeKey)) {
            return new ProcessingResult(false, true, 0, "duplicate user websocket event");
        }

        UserWebSocketEventEntity event = UserWebSocketEventEntity.from(
                dedupeKey,
                message,
                rawPayload,
                connectionGeneration,
                effectiveReceivedAt
        );
        eventRepository.save(event);

        Map<Long, TradeOrderEntity> exactMatches = exactOrderMatches(message);
        if (exactMatches.isEmpty()) {
            event.markUnresolved("no local order matched an exact remote order id");
            eventRepository.save(event);
            return new ProcessingResult(true, false, 0, event.getProcessingNote());
        }

        Instant eventAt = message.eventTimestamp() == null ? effectiveReceivedAt : message.eventTimestamp();
        for (TradeOrderEntity order : exactMatches.values()) {
            order.recordUserWebSocketEvent(
                    message.eventType(),
                    message.lifecycleStatus(),
                    message.remoteTradeId(),
                    eventAt,
                    connectionGeneration
            );
            projectOrderLifecycle(message, rawPayload, order);
            tradeOrderRepository.save(order);
        }

        Long singleOrderId = exactMatches.size() == 1
                ? exactMatches.values().iterator().next().getId()
                : null;
        String note = exactMatches.size() == 1
                ? "projected exact remote-order user websocket event"
                : "projected event to " + exactMatches.size() + " exact remote-order matches";
        event.markProcessed(singleOrderId, note);
        eventRepository.save(event);
        return new ProcessingResult(true, false, exactMatches.size(), note);
    }

    private Map<Long, TradeOrderEntity> exactOrderMatches(UserWebSocketMessage message) {
        Map<Long, TradeOrderEntity> matches = new LinkedHashMap<>();
        for (String remoteOrderId : message.remoteOrderIds()) {
            tradeOrderRepository.findByRemoteOrderId(remoteOrderId)
                    .ifPresent(order -> {
                        if (order.getId() != null) {
                            matches.put(order.getId(), order);
                        }
                    });
        }
        return matches;
    }

    private void projectOrderLifecycle(
            UserWebSocketMessage message,
            String rawPayload,
            TradeOrderEntity order
    ) {
        if (!message.isOrder() || message.lifecycleStatus() == null || order.getStatus().isTerminal()) {
            return;
        }

        switch (message.lifecycleStatus().toUpperCase(Locale.ROOT)) {
            case "PLACEMENT" -> {
                if (order.getStatus().isActive()) {
                    order.markResting(rawPayload);
                }
            }
            case "CANCELLATION" -> {
                if (message.sizeMatchedDecimal() == null || message.sizeMatchedDecimal().signum() == 0) {
                    order.markCancelled("authenticated user websocket cancellation", rawPayload);
                }
            }
            default -> {
                // UPDATE is lifecycle evidence only here. T031 owns provisional/settled fill semantics.
            }
        }
    }

    private String dedupeKey(UserWebSocketMessage message, String rawPayload) {
        String canonical;
        if (message.isTrade() && message.remoteTradeId() != null) {
            canonical = "trade|" + message.remoteTradeId() + "|" + nullSafe(message.lifecycleStatus());
        } else if (message.isOrder() && message.id() != null) {
            canonical = "order|" + message.id()
                    + "|" + nullSafe(message.lifecycleStatus())
                    + "|" + nullSafe(message.sizeMatched())
                    + "|" + nullSafe(message.timestamp())
                    + "|" + nullSafe(message.createdAt());
        } else {
            canonical = "raw|" + nullSafe(rawPayload);
        }
        return sha256(canonical);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ProcessingResult(
            boolean persisted,
            boolean duplicate,
            int projectedOrderCount,
            String note
    ) {
    }
}
