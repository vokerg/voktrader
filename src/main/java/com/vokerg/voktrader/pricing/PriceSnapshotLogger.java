package com.vokerg.voktrader.pricing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceSnapshotLogger {

    private final LatestPriceState latestPriceState;

    @Scheduled(fixedRate = 2000)
    public void logSnapshot() {
        var up = latestPriceState.byOutcome("Up").orElse(null);
        var down = latestPriceState.byOutcome("Down").orElse(null);

        if (up == null || down == null) {
            return;
        }

        log.info(
                "SNAPSHOT Up {}/{} spread={} | Down {}/{} spread={}",
                up.bid(),
                up.ask(),
                up.spread(),
                down.bid(),
                down.ask(),
                down.spread()
        );
    }
}