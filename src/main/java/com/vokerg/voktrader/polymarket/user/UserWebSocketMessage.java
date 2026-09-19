package com.vokerg.voktrader.polymarket.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserWebSocketMessage(
        @JsonProperty("event_type") String eventType,
        String id,
        String market,
        @JsonProperty("asset_id") String assetId,
        String side,
        String price,
        String size,
        String status,
        String type,
        @JsonProperty("original_size") String originalSize,
        @JsonProperty("size_matched") String sizeMatched,
        @JsonProperty("taker_order_id") String takerOrderId,
        @JsonProperty("maker_orders") List<MakerOrder> makerOrders,
        String timestamp,
        @JsonProperty("created_at") String createdAt
) {
    public boolean isOrder() {
        return "order".equalsIgnoreCase(eventType);
    }

    public boolean isTrade() {
        return "trade".equalsIgnoreCase(eventType);
    }

    public String lifecycleStatus() {
        if (isTrade()) {
            return normalize(status);
        }
        if (isOrder()) {
            return normalize(type);
        }
        return null;
    }

    public String remoteTradeId() {
        return isTrade() ? normalized(id) : null;
    }

    public Set<String> remoteOrderIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (isOrder()) {
            add(ids, id);
        } else if (isTrade()) {
            add(ids, takerOrderId);
            if (makerOrders != null) {
                makerOrders.forEach(order -> {
                    if (order != null) {
                        add(ids, order.orderId());
                    }
                });
            }
        }
        return Set.copyOf(ids);
    }

    public BigDecimal sizeMatchedDecimal() {
        return decimal(sizeMatched);
    }

    public Instant eventTimestamp() {
        Instant parsed = parseTimestamp(timestamp);
        return parsed != null ? parsed : parseTimestamp(createdAt);
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long raw = Long.parseLong(value);
            return value.length() <= 10 ? Instant.ofEpochSecond(raw) : Instant.ofEpochMilli(raw);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static void add(Set<String> ids, String value) {
        String normalized = normalized(value);
        if (normalized != null) {
            ids.add(normalized);
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalize(String value) {
        String normalized = normalized(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MakerOrder(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("matched_amount") String matchedAmount,
            String price
    ) {
    }
}
