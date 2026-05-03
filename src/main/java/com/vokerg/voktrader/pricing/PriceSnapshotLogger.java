package com.vokerg.voktrader.pricing;

import com.vokerg.voktrader.bot.BotRuntime;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.bot.BotRuntimeManager;
import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final PriceSnapshotService priceSnapshotService;
    private final ObjectProvider<BotRuntimeManager> botRuntimeManagerProvider;
    private final TradingEventLogger eventLogger;

    @Scheduled(fixedRate = 2000)
    public void logSnapshot() {
        BotRuntimeManager botRuntimeManager = botRuntimeManagerProvider.getIfAvailable();
        if (botRuntimeManager != null && !botRuntimeManager.runtimes().isEmpty()) {
            for (BotRuntime runtime : botRuntimeManager.runtimes()) {
                BotRuntimeContextHolder.runWith(runtime.context(), () -> logSnapshotForScope("botId=" + runtime.botId()));
            }
            return;
        }
        logSnapshotForScope("legacy");
    }

    private void logSnapshotForScope(String scope) {
        if (trackedMarketState.isResolved()) {
            return;
        }

        var up = latestPriceState.byOutcome("Up").orElse(null);
        var down = latestPriceState.byOutcome("Down").orElse(null);

        if (up == null || down == null) {
            return;
        }

        var market = trackedMarketState.currentMarket().orElse(null);
        Instant capturedAt = Instant.now();
        String marketId = market == null ? null : market.id();
        Duration remainingDuration = market == null || market.endDate() == null
                ? null
                : Duration.between(capturedAt, market.endDate());
        String remaining = formatRemaining(remainingDuration);

        log.info(
                "{}SNAPSHOT scope={} marketId={} remaining={} | Up {}/{} spread={} | Down {}/{} spread={}{}",
                LogColors.SNAPSHOT,
                scope,
                marketId,
                remaining,
                up.bid(),
                up.ask(),
                up.spread(),
                down.bid(),
                down.ask(),
                down.spread(),
                LogColors.RESET
        );
        eventLogger.market(
                "PRICE_SNAPSHOT",
                BotRuntimeContextHolder.currentBotId().orElse(null),
                market,
                "scheduled snapshot",
                TelemetryData.data(
                        "scope", scope,
                        "remaining", remaining,
                        "remainingSeconds", remainingDuration == null ? null : remainingDuration.getSeconds(),
                        "upTokenId", up.tokenId(),
                        "upBid", up.bid(),
                        "upAsk", up.ask(),
                        "upSpread", up.spread(),
                        "downTokenId", down.tokenId(),
                        "downBid", down.bid(),
                        "downAsk", down.ask(),
                        "downSpread", down.spread()
                ),
                true
        );

        priceSnapshotService.saveSnapshot(marketId, remainingDuration, up, down, capturedAt);
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
