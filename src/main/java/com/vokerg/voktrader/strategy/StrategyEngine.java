package com.vokerg.voktrader.strategy;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyRegistry strategyRegistry;
    private final StrategyProperties strategyProperties;

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
        TradingStrategy activeStrategy = strategyRegistry.activeStrategy();

        try {
            activeStrategy.tick();
        } catch (Exception ex) {
            log.error("Strategy tick failed for strategyId={}", activeStrategy.id(), ex);
        }
    }
}
