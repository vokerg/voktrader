package com.vokerg.voktrader.marketdata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record MarketTokenMap(
        List<String> tokenIds,
        Map<String, String> outcomeByTokenId
) {
    public static MarketTokenMap from(List<String> tokenIds, List<String> outcomes) {
        Map<String, String> outcomeByTokenId = new HashMap<>();
        int count = Math.min(tokenIds.size(), outcomes.size());
        for (int i = 0; i < count; i++) {
            outcomeByTokenId.put(tokenIds.get(i), outcomes.get(i));
        }
        return new MarketTokenMap(List.copyOf(tokenIds), Map.copyOf(outcomeByTokenId));
    }

    public String outcomeFor(String tokenId) {
        return outcomeByTokenId.get(tokenId);
    }
}
