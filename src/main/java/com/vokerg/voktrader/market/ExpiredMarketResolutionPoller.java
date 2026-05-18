package com.vokerg.voktrader.market;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.config.ResolutionProperties;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "voktrader.resolution.poller", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExpiredMarketResolutionPoller {

    private final ResolutionProperties resolutionProperties;
    private final MarketRepository marketRepository;
    private final MarketPersistenceService marketPersistenceService;
    private final GammaClient gammaClient;
    private final ObjectMapper objectMapper;
    private final MarketResolutionService marketResolutionService;

    @Scheduled(fixedDelayString = "${voktrader.resolution.poll-ms:30000}", initialDelayString = "${voktrader.resolution.poll-ms:30000}")
    public void pollExpiredMarkets() {
        Instant cutoff = Instant.now().minus(resolutionProperties.grace());
        List<MarketEntity> markets = marketRepository.findTop10ByEndDateBeforeAndResolutionStatusOrderByEndDateAsc(
                cutoff,
                MarketResolutionStatus.UNRESOLVED);

        markets.stream()
                .limit(resolutionProperties.maxMarketsPerRunOrDefault())
                .forEach(this::pollMarket);
    }

    private void pollMarket(MarketEntity market) {
        try {
            gammaClient.getMarketById(market.getPolymarketMarketId())
                    .switchIfEmpty(Mono.defer(() -> market.getSlug() == null || market.getSlug().isBlank()
                            ? Mono.empty()
                            : gammaClient.getMarketBySlug(market.getSlug())))
                    .blockOptional()
                    .flatMap(gammaMarket -> gammaMarket.resolvedOutcome(objectMapper))
                    .ifPresentOrElse(
                            winner -> {
                                log.info(
                                        "{}EMERGENCY MARKET RESOLUTION found via gamma_poll: marketId={} winningOutcome={} winningAssetId={} attempts={}{}",
                                        LogColors.TRADE,
                                        market.getPolymarketMarketId(),
                                        winner.winningOutcome(),
                                        winner.winningAssetId(),
                                        nextAttempts(market),
                                        LogColors.RESET);
                                marketResolutionService.resolveMarket(
                                        market.getPolymarketMarketId(),
                                        winner.winningAssetId(),
                                        winner.winningOutcome(),
                                        "gamma_poll");
                            },
                            () -> recordNoResolution(market));
        } catch (Exception ex) {
            marketPersistenceService.recordResolutionCheck(market.getPolymarketMarketId(), true);
            log.warn(
                    "{}No resolution yet: marketId={} attempts={}{}",
                    LogColors.TRADE,
                    market.getPolymarketMarketId(),
                    nextAttempts(market),
                    LogColors.RESET,
                    ex);
        }
    }

    private void recordNoResolution(MarketEntity market) {
        marketPersistenceService.recordResolutionCheck(market.getPolymarketMarketId(), false);
        log.info(
                "{}No resolution yet: marketId={} attempts={}{}",
                LogColors.TRADE,
                market.getPolymarketMarketId(),
                nextAttempts(market),
                LogColors.RESET);
    }

    private int nextAttempts(MarketEntity market) {
        return (market.getResolutionAttempts() == null ? 0 : market.getResolutionAttempts()) + 1;
    }
}
