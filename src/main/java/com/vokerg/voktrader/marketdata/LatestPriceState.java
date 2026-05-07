package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.time.TimeMachine;
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
        update(tokenId, outcome, bid, ask, TimeMachine.now());
    }

    public void update(String tokenId, String outcome, BigDecimal bid, BigDecimal ask, Instant updatedAt) {
        Optional<LatestPriceState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().update(tokenId, outcome, bid, ask, updatedAt);
            return;
        }
        if (tokenId == null || outcome == null) {
            return;
        }
        BigDecimal spread = null;
        if (bid != null && ask != null) {
            spread = ask.subtract(bid);
        }
        byTokenId.put(tokenId, new OutcomePrice(tokenId, outcome, bid, ask, spread, updatedAt == null ? TimeMachine.now() : updatedAt));
    }

    public Optional<OutcomePrice> byTokenId(String tokenId) {
        Optional<LatestPriceState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().byTokenId(tokenId);
        }
        return Optional.ofNullable(byTokenId.get(tokenId));
    }

    public Optional<OutcomePrice> byOutcome(String outcome) {
        Optional<LatestPriceState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().byOutcome(outcome);
        }
        return byTokenId.values().stream()
                .filter(price -> price.outcome().equalsIgnoreCase(outcome))
                .findFirst();
    }

    public Map<String, OutcomePrice> allByTokenId() {
        Optional<LatestPriceState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().allByTokenId();
        }
        return Map.copyOf(byTokenId);
    }

    public void clear() {
        Optional<LatestPriceState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().clear();
            return;
        }
        byTokenId.clear();
    }

    private Optional<LatestPriceState> delegate() {
        return BotRuntimeContextHolder.currentLatestPriceState()
                .filter(state -> state != this);
    }
}
