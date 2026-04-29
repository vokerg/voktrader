package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

        Boolean resolved,

        @JsonProperty("clobTokenIds")
        JsonNode clobTokenIds,

        JsonNode outcomes,

        @JsonProperty("outcomePrices")
        JsonNode outcomePrices,

        JsonNode tokens
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

    public Optional<ResolvedOutcome> resolvedOutcome(ObjectMapper objectMapper) {
        Optional<ResolvedOutcome> tokenWinner = resolvedOutcomeFromTokens();
        if (tokenWinner.isPresent()) {
            return tokenWinner;
        }

        if (!Boolean.TRUE.equals(resolved) && !Boolean.TRUE.equals(closed)) {
            return Optional.empty();
        }

        List<String> tokenIds = tokenIds(objectMapper);
        List<String> names = outcomeNames(objectMapper);
        List<String> prices = jsonStringList(outcomePrices, objectMapper);

        int count = Math.min(Math.min(tokenIds.size(), names.size()), prices.size());

        for (int i = 0; i < count; i++) {
            if ("1".equals(prices.get(i)) || "1.0".equals(prices.get(i)) || "1.00".equals(prices.get(i))) {
                return Optional.of(new ResolvedOutcome(tokenIds.get(i), names.get(i)));
            }
        }

        return Optional.empty();
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

    private Optional<ResolvedOutcome> resolvedOutcomeFromTokens() {
        if (tokens == null || !tokens.isArray()) {
            return Optional.empty();
        }

        for (JsonNode token : tokens) {
            boolean winner = token.path("winner").asBoolean(false)
                    || token.path("winning").asBoolean(false);

            if (!winner) {
                continue;
            }

            String tokenId = firstText(
                    token.path("token_id"),
                    token.path("tokenId"),
                    token.path("asset_id"),
                    token.path("assetId"),
                    token.path("id")
            );
            String outcome = firstText(token.path("outcome"), token.path("name"));

            if (outcome != null && !outcome.isBlank()) {
                return Optional.of(new ResolvedOutcome(tokenId, outcome));
            }
        }

        return Optional.empty();
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }

            String value = node.asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    public record ResolvedOutcome(String winningAssetId, String winningOutcome) {
    }
}
