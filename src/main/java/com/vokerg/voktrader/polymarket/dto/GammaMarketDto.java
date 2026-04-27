package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GammaMarketDto(
        String id,
        String question,

        @JsonProperty("conditionId")
        String conditionId,

        String slug,

        @JsonProperty("endDate")
        Instant endDate,

        Boolean active,
        Boolean closed,

        @JsonProperty("acceptingOrders")
        Boolean acceptingOrders,

        @JsonProperty("clobTokenIds")
        JsonNode clobTokenIds,

        JsonNode outcomes
) {
    public List<String> tokenIds(ObjectMapper objectMapper) {
        return jsonStringList(clobTokenIds, objectMapper);
    }

    public List<String> outcomeNames(ObjectMapper objectMapper) {
        return jsonStringList(outcomes, objectMapper);
    }

    public boolean isActiveOpenMarket() {
        return Boolean.TRUE.equals(active) && !Boolean.TRUE.equals(closed);
    }

    public boolean acceptsOrders() {
        return Boolean.TRUE.equals(acceptingOrders);
    }

    public boolean endsAfter(Instant instant) {
        return endDate != null && endDate.isAfter(instant);
    }

    public boolean looksLikeBitcoinMarket() {
        String q = question == null ? "" : question.toLowerCase();
        String s = slug == null ? "" : slug.toLowerCase();

        return q.contains("bitcoin")
                || q.contains("btc")
                || s.contains("bitcoin")
                || s.contains("btc");
    }

    public boolean looksLikeBitcoinUpDownMarket() {
        String q = question == null ? "" : question.toLowerCase();
        String s = slug == null ? "" : slug.toLowerCase();

        boolean mentionsBitcoin = q.contains("bitcoin")
                || q.contains("btc")
                || s.contains("bitcoin")
                || s.contains("btc");

        boolean mentionsUpDown = q.contains("up or down")
                || s.contains("up-or-down")
                || s.contains("updown");

        return mentionsBitcoin && mentionsUpDown;
    }

    private static List<String> jsonStringList(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return List.of();
        }

        try {
            if (node.isArray()) {
                List<String> result = new ArrayList<>();
                node.forEach(item -> result.add(item.asText()));
                return result;
            }

            if (node.isTextual()) {
                String raw = node.asText();

                if (raw == null || raw.isBlank()) {
                    return List.of();
                }

                JsonNode parsed = objectMapper.readTree(raw);

                if (!parsed.isArray()) {
                    return List.of();
                }

                List<String> result = new ArrayList<>();
                parsed.forEach(item -> result.add(item.asText()));
                return result;
            }

            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}