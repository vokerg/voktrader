package com.vokerg.voktrader.pricing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LatestPriceState {

    private final Map<String, OutcomePrice> byTokenId = new ConcurrentHashMap<>();

    public void update(String tokenId, String outcome, BigDecimal bid, BigDecimal ask) {
        if (tokenId == null || outcome == null) {
            return;
        }

        BigDecimal spread = null;
        if (bid != null && ask != null) {
            spread = ask.subtract(bid);
        }

        byTokenId.put(tokenId, new OutcomePrice(
                tokenId,
                outcome,
                bid,
                ask,
                spread,
                Instant.now()
        ));
    }

    public Optional<OutcomePrice> byTokenId(String tokenId) {
        return Optional.ofNullable(byTokenId.get(tokenId));
    }

    public Optional<OutcomePrice> byOutcome(String outcome) {
        return byTokenId.values().stream()
                .filter(price -> price.outcome().equalsIgnoreCase(outcome))
                .findFirst();
    }

    public Map<String, OutcomePrice> allByTokenId() {
        return Map.copyOf(byTokenId);
    }

    public void clear() {
        byTokenId.clear();
    }
}