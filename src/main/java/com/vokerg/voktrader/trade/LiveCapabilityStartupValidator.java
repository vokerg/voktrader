package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveCapabilityStartupValidator implements ApplicationRunner {
    private final TradingProperties tradingProperties;
    private final LiveArmService liveArmService;

    @Override
    public void run(ApplicationArguments args) {
        if (tradingProperties.getMode() != ExecutionMode.LIVE) {
            return;
        }

        LiveArmService.LiveArmStatus status = liveArmService.status();
        if (status.capabilityReady()) {
            log.info("LIVE profile started capability-ready but unarmed; an explicit expiring arm is required before entries");
            return;
        }

        log.warn("LIVE profile started unarmed with capability blockers={}", status.capabilityBlockers());
    }
}
