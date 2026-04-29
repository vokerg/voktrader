package com.vokerg.voktrader.market;

import com.vokerg.voktrader.PaperBotRunner;
import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLifecycleService {

    private final TrackedMarketState trackedMarketState;
    private final MarketSelectionProperties marketSelectionProperties;
    private final PaperBotRunner paperBotRunner;

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void rollExpiredCurrentMarket() {
        GammaMarketDto current = trackedMarketState.currentMarket().orElse(null);
        if (current == null || current.endDate() == null) {
            return;
        }

        Instant now = Instant.now();
        Instant rolloverAt = current.endDate().plus(marketSelectionProperties.expiryGrace());

        if (now.isBefore(rolloverAt)) {
            return;
        }

        log.info(
                "{}Market expired grace elapsed: marketId={} endDate={} now={} rolling to next market{}",
                LogColors.MARKET,
                current.id(),
                current.endDate(),
                now,
                LogColors.RESET);

        paperBotRunner.stopCurrentMarketAndRoll(current.id(), "expired_grace_elapsed");
    }
}
