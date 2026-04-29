package com.vokerg.voktrader.pricing;

import com.vokerg.voktrader.market.TrackedMarketState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceSnapshotLogger {

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;

    @Scheduled(fixedRate = 2000)
    public void logSnapshot() {
        var up = latestPriceState.byOutcome("Up").orElse(null);
        var down = latestPriceState.byOutcome("Down").orElse(null);

        if (up == null || down == null) {
            return;
        }

        var market = trackedMarketState.currentMarket().orElse(null);
        String marketId = market == null ? null : market.id();
        String remaining = market == null
                ? null
                : formatRemaining(Duration.between(Instant.now(), market.endDate()));

        log.info(
                "SNAPSHOT marketId={} remaining={} | Up {}/{} spread={} | Down {}/{} spread={}",
                marketId,
                remaining,
                up.bid(),
                up.ask(),
                up.spread(),
                down.bid(),
                down.ask(),
                down.spread()
        );
    }

    private String formatRemaining(Duration remaining) {
        if (remaining == null) {
            return null;
        }

        long seconds = remaining.getSeconds();
        boolean negative = seconds < 0;
        seconds = Math.abs(seconds);

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        String prefix = negative ? "-" : "";

        if (hours > 0) {
            return "%s%dh%02dm%02ds".formatted(prefix, hours, minutes, remainingSeconds);
        }

        return "%s%dm%02ds".formatted(prefix, minutes, remainingSeconds);
    }
}
