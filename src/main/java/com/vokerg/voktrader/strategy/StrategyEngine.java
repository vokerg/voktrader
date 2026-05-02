package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component @ConditionalOnProperty(name = "voktrader.legacy.strategy-engine.enabled", havingValue = "true")
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyRegistry strategyRegistry;
    private final StrategyProperties strategyProperties;
    private final TrackedMarketState trackedMarketState;

    @PostConstruct
    void logConfiguration() {
        log.info(
                "Strategy engine configured: activeStrategy={} tickMs={} availableStrategies={}",
                strategyProperties.activeOrDefault(),
                strategyProperties.tickMsOrDefault(),
                strategyRegistry.strategyIds()
        );
    }

    @Scheduled(fixedRateString = "${voktrader.strategy.tick-ms:1000}")
    public void tick() {
        if (trackedMarketState.currentEndDate()
                .map(endDate -> !endDate.isAfter(Instant.now()))
                .orElse(false)) {
            log.debug("Skipping strategy tick because current market is expired");
            return;
        }

        TradingStrategy activeStrategy = strategyRegistry.activeStrategy();

        try {
            activeStrategy.tick();
        } catch (Exception ex) {
            log.error("Strategy tick failed for strategyId={}", activeStrategy.id(), ex);
        }
    }
}
