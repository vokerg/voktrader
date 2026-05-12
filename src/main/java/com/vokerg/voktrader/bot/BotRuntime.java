package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.MarketPersistenceService;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.MarketPriceFeedHandle;
import com.vokerg.voktrader.marketdata.MarketPriceFeedService;
import com.vokerg.voktrader.marketdata.MarketTokenMap;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.resolution.MarketResolutionService;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class BotRuntime {
    private final BotConfigEntity config;
    private final GammaClient gammaClient;
    private final MarketPriceFeedService marketPriceFeedService;
    private final ObjectMapper objectMapper;
    private final MarketSelectionProperties marketSelectionProperties;
    private final MarketPersistenceService marketPersistenceService;
    private final MarketResolutionService marketResolutionService;
    private final TradingEventLogger eventLogger;
    private final AtomicBoolean rolloverInProgress = new AtomicBoolean(false);
    private final LatestPriceState fallbackPriceState = new LatestPriceState();
    private final OrderBookState fallbackOrderBookState = new OrderBookState();
    private final TrackedMarketState trackedMarketState = new TrackedMarketState();
    private MarketPriceFeedHandle currentPriceFeed;

    public BotRuntime(
            BotConfigEntity config,
            GammaClient gammaClient,
            MarketPriceFeedService marketPriceFeedService,
            ObjectMapper objectMapper,
            MarketSelectionProperties marketSelectionProperties,
            MarketPersistenceService marketPersistenceService,
            MarketResolutionService marketResolutionService,
            TradingEventLogger eventLogger
    ) {
        this.config = config;
        this.gammaClient = gammaClient;
        this.marketPriceFeedService = marketPriceFeedService;
        this.objectMapper = objectMapper;
        this.marketSelectionProperties = marketSelectionProperties;
        this.marketPersistenceService = marketPersistenceService;
        this.marketResolutionService = marketResolutionService;
        this.eventLogger = eventLogger;
    }

    public Long botId() {
        return config.getId();
    }

    public BotConfigEntity config() {
        return config;
    }

    public BotRuntimeContext context() {
        return new BotRuntimeContext(
                botId(),
                config.getMarketFamily(),
                config.getStrategyId(),
                config.getStrategyConfigId(),
                config.getSubStrategyId(),
                trackedMarketState,
                currentLatestPriceState(),
                currentOrderBookState()
        );
    }

    private LatestPriceState currentLatestPriceState() {
        return currentPriceFeed == null ? fallbackPriceState : currentPriceFeed.latestPriceState();
    }

    private OrderBookState currentOrderBookState() {
        return currentPriceFeed == null ? fallbackOrderBookState : currentPriceFeed.orderBookState();
    }

    public void start(String reason) {
        log.info("{}Starting bot runtime id={} name={} family={} strategy={} strategyConfig={} subStrategy={} reason={}{}",
                LogColors.MARKET, botId(), config.getName(), config.getMarketFamily(), config.getStrategyId(), config.getStrategyConfigId(), config.getSubStrategyId(), reason, LogColors.RESET);
        eventLogger.market(
                "BOT_RUNTIME_STARTED",
                botId(),
                null,
                reason,
                TelemetryData.data("name", config.getName(), "family", config.getMarketFamily(), "strategyId", config.getStrategyId(), "strategyConfigId", config.getStrategyConfigId(), "subStrategyId", config.getSubStrategyId()),
                true
        );
        rollToNextMarket(reason);
    }

    public void ensureMarketIsTracked() {
        if (trackedMarketState.currentMarket().isEmpty()) {
            rollToNextMarket("no current market");
            return;
        }
        if (trackedMarketState.isResolved()) {
            rollToNextMarket("current market resolved");
            return;
        }
        GammaMarketDto current = trackedMarketState.currentMarket().orElse(null);
        if (current != null && expiredGraceElapsed(current)) {
            log.info("{}Bot market expired grace elapsed: botId={} marketId={} endDate={} now={} rolling to next market{}",
                    LogColors.MARKET, botId(), current.id(), current.endDate(), Instant.now(), LogColors.RESET);
            eventLogger.market(
                    "MARKET_EXPIRED_GRACE_ELAPSED",
                    botId(),
                    current,
                    "expired_grace_elapsed",
                    TelemetryData.data("endDate", current.endDate(), "now", Instant.now()),
                    true
            );
            stopCurrentMarketAndRoll(current.id(), "expired_grace_elapsed");
        }
    }

    public boolean marketExpired() {
        return trackedMarketState.currentEndDate()
                .map(endDate -> !endDate.isAfter(Instant.now()))
                .orElse(false);
    }

    private boolean expiredGraceElapsed(GammaMarketDto market) {
        if (market.endDate() == null) {
            return false;
        }
        Instant rolloverAt = market.endDate().plus(marketSelectionProperties.expiryGrace());
        return !Instant.now().isBefore(rolloverAt);
    }

    public void rollToNextMarket(String reason) {
        if (!rolloverInProgress.compareAndSet(false, true)) {
            log.debug("Rollover already in progress for botId={} reason={}", botId(), reason);
            eventLogger.market("MARKET_ROLLOVER_ALREADY_IN_PROGRESS", botId(), null, reason, Map.of(), false);
            return;
        }
        eventLogger.market(
                "MARKET_ROLLOVER_STARTED",
                botId(),
                trackedMarketState.currentMarket().orElse(null),
                reason,
                TelemetryData.data("family", config.getMarketFamily()),
                true
        );
        AtomicBoolean foundMarket = new AtomicBoolean(false);
        findConfiguredMarket()
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(market -> Mono.fromCallable(() -> {
                    foundMarket.set(true);
                    trackMarket(market);
                    return market;
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnError(error -> {
                    log.error("Failed during bot market rollover botId={}", botId(), error);
                    eventLogger.market(
                            "MARKET_ROLLOVER_FAILED",
                            botId(),
                            null,
                            reason,
                            TelemetryData.data("error", error.getMessage()),
                            true
                    );
                })
                .doFinally(signalType -> rolloverInProgress.set(false))
                .subscribe(
                        market -> {
                            log.info("{}Bot rollover complete: botId={} marketId={} slug={} question={}{}",
                                    LogColors.MARKET, botId(), market.id(), market.slug(), market.question(), LogColors.RESET);
                            eventLogger.market(
                                    "MARKET_ROLLOVER_COMPLETED",
                                    botId(),
                                    market,
                                    reason,
                                    TelemetryData.data("question", market.question(), "endDate", market.endDate()),
                                    true
                            );
                        },
                        error -> {
                            log.error("Bot rollover subscription failed botId={}", botId(), error);
                            eventLogger.market(
                                    "MARKET_ROLLOVER_SUBSCRIPTION_FAILED",
                                    botId(),
                                    null,
                                    reason,
                                    TelemetryData.data("error", error.getMessage()),
                                    true
                            );
                        },
                        () -> {
                            if (!foundMarket.get()) {
                                log.warn("{}No next market found during bot rollover botId={} family={} reason={}{}",
                                        LogColors.MARKET, botId(), config.getMarketFamily(), reason, LogColors.RESET);
                                eventLogger.market(
                                        "MARKET_ROLLOVER_NO_MARKET_FOUND",
                                        botId(),
                                        null,
                                        reason,
                                        TelemetryData.data("family", config.getMarketFamily()),
                                        true
                                );
                            }
                        });
    }

    public void stopCurrentMarketAndRoll(String marketId, String reason) {
        if (!trackedMarketState.isCurrentMarket(marketId)) {
            log.info("{}Ignoring late event for non-current bot market: botId={} marketId={} reason={}{}",
                    LogColors.MARKET, botId(), marketId, reason, LogColors.RESET);
            eventLogger.market(
                    "MARKET_LATE_STOP_IGNORED",
                    botId(),
                    trackedMarketState.currentMarket().orElse(null),
                    reason,
                    TelemetryData.data("marketId", marketId),
                    false
            );
            return;
        }
        detachCurrentPriceFeedForResolution();
        marketPersistenceService.markStopped(marketId);
        trackedMarketState.clearIfCurrent(marketId);
        fallbackPriceState.clear();
        fallbackOrderBookState.clear();
        rollToNextMarket(reason);
    }

    public void shutdown() {
        releaseCurrentPriceFeed();
    }

    private Mono<GammaMarketDto> findConfiguredMarket() {
        MarketFamily family = config.getMarketFamily();
        return Flux.fromIterable(family.candidateSlugs(Instant.now(), 6))
                .concatMap(slug -> gammaClient.getMarketBySlug(slug)
                        .onErrorResume(error -> {
                            log.debug("Failed slug lookup for botId={} slug={}", botId(), slug, error);
                            return Mono.empty();
                        }))
                .filter(GammaMarketDto::isActiveOpenMarket)
                .filter(GammaMarketDto::acceptsOrders)
                .filter(market -> market.endsAfter(Instant.now()))
                .filter(this::matchesConfiguredMarketFamily)
                .filter(this::hasEnoughTimeRemainingForSetup)
                .filter(this::isNotAlreadyResolvedCurrentMarket)
                .next()
                .switchIfEmpty(Mono.defer(this::findConfiguredMarketFromFallback));
    }

    private Mono<GammaMarketDto> findConfiguredMarketFromFallback() {
        MarketFamily family = config.getMarketFamily();
        log.warn("{}No suitable market found by deterministic slug lookup for botId={} family={}. Fallback query={}{}",
                LogColors.MARKET, botId(), family, family.searchQuery(), LogColors.RESET);
        if (family.asset() != BotAsset.BTC) {
            return Mono.empty();
        }
        eventLogger.market(
                "MARKET_SLUG_LOOKUP_FALLBACK",
                botId(),
                null,
                "deterministic slug lookup empty",
                TelemetryData.data("family", family, "query", family.searchQuery()),
                true
        );
        return gammaClient.searchBitcoinUpDownMarkets()
                .filter(this::matchesConfiguredMarketFamily)
                .filter(this::hasEnoughTimeRemainingForSetup)
                .filter(this::isNotAlreadyResolvedCurrentMarket)
                .next();
    }

    private void trackMarket(GammaMarketDto market) {
        if (market == null) {
            return;
        }
        releaseCurrentPriceFeed();
        fallbackPriceState.clear();
        fallbackOrderBookState.clear();
        trackedMarketState.startTracking(market);
        var savedMarket = marketPersistenceService.saveOrUpdate(market);
        List<String> tokenIds = market.tokenIds(objectMapper);
        List<String> outcomes = market.outcomeNames(objectMapper);
        if (tokenIds.size() < 2 || outcomes.size() < 2) {
            log.warn("Market did not have enough token/outcome data: botId={} question={} tokenIds={} outcomes={}",
                    botId(), market.question(), tokenIds, outcomes);
            eventLogger.market(
                    "MARKET_TRACKING_REJECTED",
                    botId(),
                    market,
                    "not enough token/outcome data",
                    TelemetryData.data("question", market.question(), "tokenIds", tokenIds, "outcomes", outcomes),
                    true
            );
            return;
        }
        MarketTokenMap tokenMap = MarketTokenMap.from(tokenIds, outcomes);
        Duration remaining = market.endDate() == null ? null : Duration.between(Instant.now(), market.endDate());
        log.info("{}Tracking bot market botId={} marketId={} dbId={} slug={} family={} endDate={} remaining={} tokenOutcomeMap={}{}",
                LogColors.MARKET, botId(), market.id(), savedMarket.getId(), market.slug(), config.getMarketFamily(),
                market.endDate(), remaining, tokenMap.outcomeByTokenId(), LogColors.RESET);
        eventLogger.market(
                "MARKET_TRACKING_STARTED",
                botId(),
                market,
                "tracking selected market",
                TelemetryData.data(
                        "dbId", savedMarket.getId(),
                        "family", config.getMarketFamily(),
                        "question", market.question(),
                        "endDate", market.endDate(),
                        "remaining", remaining,
                        "outcomeByTokenId", tokenMap.outcomeByTokenId()
                ),
                true
        );
        currentPriceFeed = marketPriceFeedService.acquire(botId(), market, tokenMap, this::handleMarketResolved);
    }

    private boolean matchesConfiguredMarketFamily(GammaMarketDto market) {
        boolean matches = config.getMarketFamily().matchesSlug(market.slug());
        if (!matches) {
            log.debug("Skipping market because family does not match: botId={} family={} slug={} question={}",
                    botId(), config.getMarketFamily(), market.slug(), market.question());
        }
        return matches;
    }

    private boolean hasEnoughTimeRemainingForSetup(GammaMarketDto market) {
        if (market.endDate() == null) {
            return false;
        }
        Duration remaining = Duration.between(Instant.now(), market.endDate());
        boolean hasEnoughTime = remaining.compareTo(marketSelectionProperties.minRemaining()) >= 0;
        if (!hasEnoughTime) {
            log.info("{}Skipping market too close to expiry: botId={} question={} slug={} remaining={} minRemaining={}{}",
                    LogColors.MARKET, botId(), market.question(), market.slug(), remaining,
                    marketSelectionProperties.minRemaining(), LogColors.RESET);
        }
        return hasEnoughTime;
    }

    private boolean isNotAlreadyResolvedCurrentMarket(GammaMarketDto candidate) {
        if (candidate == null || !trackedMarketState.isResolved()) {
            return true;
        }
        GammaMarketDto current = trackedMarketState.currentMarket().orElse(null);
        if (current == null) {
            return true;
        }
        boolean sameId = current.id() != null && current.id().equals(candidate.id());
        boolean sameSlug = current.slug() != null && current.slug().equals(candidate.slug());
        return !sameId && !sameSlug;
    }

    private void handleMarketResolved(String subscriptionMarketId, MarketWsMessageDto message) {
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        String resolvedMarketId = firstPresent(subscriptionMarketId, message.market(), market == null ? null : market.id());
        if (resolvedMarketId == null || resolvedMarketId.isBlank()) {
            log.warn("{}Received market_resolved but could not determine market id botId={}{}",
                    LogColors.TRADE, botId(), LogColors.RESET);
            eventLogger.market(
                    "MARKET_RESOLUTION_REJECTED",
                    botId(),
                    market,
                    "missing market id",
                    TelemetryData.data("subscriptionMarketId", subscriptionMarketId, "eventType", message.eventType()),
                    true
            );
            return;
        }
        if (message.winningOutcome() == null || message.winningOutcome().isBlank()) {
            log.warn("{}Received market_resolved without winningOutcome botId={} marketId={}{}",
                    LogColors.TRADE, botId(), resolvedMarketId, LogColors.RESET);
            eventLogger.market(
                    "MARKET_RESOLUTION_REJECTED",
                    botId(),
                    market,
                    "missing winning outcome",
                    TelemetryData.data("marketId", resolvedMarketId, "winningAssetId", message.winningAssetId()),
                    true
            );
            return;
        }
        eventLogger.market(
                "MARKET_RESOLVED_WS",
                botId(),
                market,
                "websocket resolution",
                TelemetryData.data(
                        "marketId", resolvedMarketId,
                        "winningAssetId", message.winningAssetId(),
                        "winningOutcome", message.winningOutcome()
                ),
                true
        );
        marketResolutionService.resolveMarket(resolvedMarketId, message.winningAssetId(), message.winningOutcome(), "websocket");
        if (!trackedMarketState.isCurrentMarket(resolvedMarketId)) {
            return;
        }
        trackedMarketState.markResolved(message.winningOutcome());
        releaseCurrentPriceFeed();
        rollToNextMarket("market_resolved");
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void releaseCurrentPriceFeed() {
        if (currentPriceFeed != null) {
            currentPriceFeed.close();
            currentPriceFeed = null;
            eventLogger.market(
                    "MARKET_PRICE_FEED_RELEASED",
                    botId(),
                    trackedMarketState.currentMarket().orElse(null),
                    "bot released shared market price feed",
                    Map.of(),
                    false
            );
        }
    }

    private void detachCurrentPriceFeedForResolution() {
        if (currentPriceFeed != null) {
            log.info("{}Keeping expired shared market price feed for resolution only: botId={} marketId={}{}",
                    LogColors.MARKET, botId(), currentPriceFeed.marketId(), LogColors.RESET);
            eventLogger.market(
                    "MARKET_PRICE_FEED_RESOLUTION_ONLY",
                    botId(),
                    trackedMarketState.currentMarket().orElse(null),
                    "keeping expired shared market price feed for resolution",
                    TelemetryData.data("marketId", currentPriceFeed.marketId()),
                    true
            );
            currentPriceFeed = null;
        }
    }
}
