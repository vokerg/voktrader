package com.vokerg.voktrader.resolution;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.market.MarketRepository;
import com.vokerg.voktrader.market.MarketResolutionStatus;
import com.vokerg.voktrader.market.MarketTrackingStatus;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.TradeLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketResolutionService {

    private final MarketRepository marketRepository;
    private final TradeLifecycleService tradeLifecycleService;
    private final TradingEventLogger eventLogger;

    @Transactional
    public void resolveMarket(
            String marketId,
            String winningAssetId,
            String winningOutcome,
            String source
    ) {
        if (marketId == null || marketId.isBlank() || winningOutcome == null || winningOutcome.isBlank()) {
            return;
        }

        MarketEntity market = marketRepository.findByPolymarketMarketId(marketId)
                .orElseGet(() -> {
                    MarketEntity created = new MarketEntity();
                    created.setPolymarketMarketId(marketId);
                    created.setFirstSeenAt(Instant.now());
                    return created;
                });

        if (MarketResolutionStatus.RESOLVED.equals(market.getResolutionStatus()) || market.isResolved()) {
            log.info(
                    "{}Ignoring duplicate market resolution: marketId={} winningOutcome={} source={}{}",
                    LogColors.TRADE,
                    marketId,
                    market.getWinningOutcome(),
                    source,
                    LogColors.RESET);
            eventLogger.market(
                    "MARKET_RESOLUTION_DUPLICATE",
                    null,
                    null,
                    "duplicate resolution",
                    TelemetryData.data(
                            "marketId", marketId,
                            "winningOutcome", market.getWinningOutcome(),
                            "source", source
                    ),
                    true
            );
            return;
        }

        Instant now = Instant.now();

        market.setResolved(true);
        market.setClosed(true);
        market.setActive(false);
        market.setAcceptingOrders(false);
        market.setTrackingStatus(MarketTrackingStatus.STOPPED);
        market.setResolutionStatus(MarketResolutionStatus.RESOLVED);
        market.setWinningAssetId(winningAssetId);
        market.setWinningOutcome(winningOutcome);
        market.setResolvedAt(now);
        market.setLastResolutionCheckAt(now);
        market.setLastSeenAt(now);

        marketRepository.save(market);

        tradeLifecycleService.resolveMarket(marketId, winningOutcome);

        log.info(
                "{}Resolved expired market: marketId={} winningOutcome={} winningAssetId={} source={}{}",
                LogColors.TRADE,
                marketId,
                winningOutcome,
                winningAssetId,
                source,
                LogColors.RESET);
        eventLogger.market(
                "MARKET_RESOLVED",
                null,
                null,
                "market resolved",
                TelemetryData.data(
                        "marketId", marketId,
                        "winningOutcome", winningOutcome,
                        "winningAssetId", winningAssetId,
                        "source", source,
                        "resolvedAt", now
                ),
                true
        );
    }
}
