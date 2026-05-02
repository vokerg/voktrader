package com.vokerg.voktrader.market;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TrackedMarketState {
    private final AtomicReference<TrackedMarketSnapshot> snapshot = new AtomicReference<>();

    public void setCurrentMarket(GammaMarketDto market) {
        startTracking(market);
    }

    public void startTracking(GammaMarketDto market) {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().startTracking(market);
            return;
        }
        snapshot.set(new TrackedMarketSnapshot(market, false, null, null));
    }

    public Optional<GammaMarketDto> currentMarket() {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().currentMarket();
        }
        return Optional.ofNullable(snapshot.get()).map(TrackedMarketSnapshot::market);
    }

    public Optional<Instant> currentEndDate() {
        return currentMarket().map(GammaMarketDto::endDate);
    }

    public boolean isCurrentMarket(String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return false;
        }
        return currentMarket().map(GammaMarketDto::id).map(marketId::equals).orElse(false);
    }

    public boolean isResolved() {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().isResolved();
        }
        TrackedMarketSnapshot current = snapshot.get();
        return current != null && current.resolved();
    }

    public Optional<String> winningOutcome() {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().winningOutcome();
        }
        return Optional.ofNullable(snapshot.get()).map(TrackedMarketSnapshot::winningOutcome);
    }

    public Optional<Instant> resolvedAt() {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().resolvedAt();
        }
        return Optional.ofNullable(snapshot.get()).map(TrackedMarketSnapshot::resolvedAt);
    }

    public void markResolved(String winningOutcome) {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().markResolved(winningOutcome);
            return;
        }
        snapshot.updateAndGet(current -> {
            if (current == null) {
                return null;
            }
            return new TrackedMarketSnapshot(current.market(), true, winningOutcome, Instant.now());
        });
    }

    public void clearIfCurrent(String marketId) {
        Optional<TrackedMarketState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().clearIfCurrent(marketId);
            return;
        }
        snapshot.updateAndGet(current -> {
            if (current == null || current.market() == null) {
                return current;
            }
            if (marketId != null && marketId.equals(current.market().id())) {
                return null;
            }
            return current;
        });
    }

    private Optional<TrackedMarketState> delegate() {
        return BotRuntimeContextHolder.currentTrackedMarketState()
                .filter(state -> state != this);
    }

    private record TrackedMarketSnapshot(
            GammaMarketDto market,
            boolean resolved,
            String winningOutcome,
            Instant resolvedAt
    ) {
    }
}
