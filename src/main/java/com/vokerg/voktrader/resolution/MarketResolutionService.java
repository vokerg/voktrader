package com.vokerg.voktrader.resolution;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.market.MarketRepository;
import com.vokerg.voktrader.market.MarketResolutionStatus;
import com.vokerg.voktrader.market.MarketTrackingStatus;
import com.vokerg.voktrader.paper.SignalService;
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
    private final SignalService signalService;

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

        signalService.resolveMarket(marketId, winningOutcome);

        log.info(
                "{}Resolved expired market: marketId={} winningOutcome={} winningAssetId={} source={}{}",
                LogColors.TRADE,
                marketId,
                winningOutcome,
                winningAssetId,
                source,
                LogColors.RESET);
    }
}
