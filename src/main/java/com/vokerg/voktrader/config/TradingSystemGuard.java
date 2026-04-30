package com.vokerg.voktrader.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TradingSystemGuard implements ApplicationRunner {
    private final Environment environment;

    public TradingSystemGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean legacySignalsEnabled = environment.getProperty(
                "voktrader.legacy.signals.enabled",
                Boolean.class,
                false
        );
        boolean tradePipelineEnabled = environment.getProperty("voktrader.trading.mode") != null;

        if (legacySignalsEnabled && tradePipelineEnabled) {
            throw new IllegalStateException(
                    "Invalid config: legacy signals and trade pipeline cannot both be enabled"
            );
        }
    }
}
