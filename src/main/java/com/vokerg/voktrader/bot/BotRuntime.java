package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.MarketPersistenceService;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.resolution.MarketResolutionService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class BotRuntime {
    private final BotConfigEntity config;
    private final GammaClient gammaClient;
    private final ClobClient clobClient;
    private final PolymarketWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final MarketSelectionProperties marketSelectionProperties;
    private final MarketPersistenceService marketPersistenceService;
    private final MarketResolutionService marketResolutionService;
    private final AtomicBoolean rolloverInProgress = new AtomicBoolean(false);
    private final Map<String, Disposable> resolutionOnlySubscriptions = new ConcurrentHashMap<>();
    private final LatestPriceState latestPriceState = new LatestPriceState();
    private final TrackedMarketState trackedMarketState = new TrackedMarketState();
    private Disposable webSocketSubscription;

    public BotRuntime(
            BotConfigEntity config,
            GammaClient gammaClient,
            ClobClient clobClient,
            PolymarketWebSocketClient webSocketClient,
            ObjectMapper objectMapper,
            MarketSelectionProperties marketSelectionProperties,
            MarketPersistenceService marketPersistenceService,
            MarketResolutionService marketResolutionService
    ) {
        this.config = config;
        this.gammaClient = gammaClient;
        this.clobClient = clobClient;
        this.webSocketClient = webSocketClient;
        this.objectMapper = objectMapper;
        this.marketSelectionProperties = marketSelectionProperties;
        this.marketPersistenceService = marketPersistenceService;
        this.marketResolutionService = marketResolutionService;
    }

    public Long botId() {
        return config.getId();
    }

    public BotConfigEntity config() {
        return config;
    }

    public BotRuntimeContext context() {
        return new BotRuntimeContext(botId(), config.getMarketFamily(), config.getStrategyId(), trackedMarketState, latestPriceState);
    }

    public void start(String reason) {
        log.info("{}Starting bot runtime id={} name={} family={} strategy={} reason={}{}",
                LogColors.MARKET, botId(), config.getName(), config.getMarketFamily(), config.getStrategyId(), reason, LogColors.RESET);
        rollToNextMarket(reason);
    }

    public void ensureMarketIsTracked() {
        if (trackedMarketState.currentMarket().isEmpty()) {
            rollToNextMarket("no current market");
            return;
        }
        if (trackedMarketState.isResolved()) {
            rollToNextMarket("current market resolved");
        }
    }

    public boolean marketExpired() {
        return trackedMarketState.currentEndDate()
                .map(endDate -> !endDate.isAfter(Instant.now()))
                .orElse(false);
    }

    public void rollToNextMarket(String reason) {
        if (!rolloverInProgress.compareAndSet(false, true)) {
            log.debug("Rollover already in progress for botId={} reason={}", botId(), reason);
            return;
        }
        AtomicBoolean foundMarket = new AtomicBoolean(false);
        findConfiguredMarket()
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(market -> Mono.fromCallable(() -> {
                    foundMarket.set(true);
                    trackMarket(market);
                    return market;
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnError(error -> log.error("Failed during bot market rollover botId={}", botId(), error))
                .doFinally(signalType -> rolloverInProgress.set(false))
                .subscribe(
                        market -> log.info("{}Bot rollover complete: botId={} marketId={} slug={} question={}{}",
                                LogColors.MARKET, botId(), market.id(), market.slug(), market.question(), LogColors.RESET),
                        error -> log.error("Bot rollover subscription failed botId={}", botId(), error),
                        () -> {
                            if (!foundMarket.get()) {
                                log.warn("{}No next market found during bot rollover botId={} family={} reason={}{}",
                                        LogColors.MARKET, botId(), config.getMarketFamily(), reason, LogColors.RESET);
                            }
                        });
    }

    public void stopCurrentMarketAndRoll(String marketId, String reason) {
        if (!trackedMarketState.isCurrentMarket(marketId)) {
            log.info("{}Ignoring late event for non-current bot market: botId={} marketId={} reason={}{}",
                    LogColors.MARKET, botId(), marketId, reason, LogColors.RESET);
            return;
        }
        keepCurrentWebSocketForResolution(marketId);
        marketPersistenceService.markStopped(marketId);
        trackedMarketState.clearIfCurrent(marketId);
        latestPriceState.clear();
        rollToNextMarket(reason);
    }

    public void shutdown() {
        disposeWebSocketSubscription();
        resolutionOnlySubscriptions.forEach((marketId, subscription) -> {
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
                log.info("Disposed resolution-only WebSocket subscription botId={} marketId={}", botId(), marketId);
            }
        });
        resolutionOnlySubscriptions.clear();
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
        disposeWebSocketSubscription();
        latestPriceState.clear();
        trackedMarketState.startTracking(market);
        var savedMarket = marketPersistenceService.saveOrUpdate(market);
        List<String> tokenIds = market.tokenIds(objectMapper);
        List<String> outcomes = market.outcomeNames(objectMapper);
        if (tokenIds.size() < 2 || outcomes.size() < 2) {
            log.warn("Market did not have enough token/outcome data: botId={} question={} tokenIds={} outcomes={}",
                    botId(), market.question(), tokenIds, outcomes);
            return;
        }
        Map<String, String> outcomeByTokenId = buildOutcomeMap(tokenIds, outcomes);
        Duration remaining = market.endDate() == null ? null : Duration.between(Instant.now(), market.endDate());
        log.info("{}Tracking bot market botId={} marketId={} dbId={} slug={} family={} endDate={} remaining={} tokenOutcomeMap={}{}",
                LogColors.MARKET, botId(), market.id(), savedMarket.getId(), market.slug(), config.getMarketFamily(),
                market.endDate(), remaining, outcomeByTokenId, LogColors.RESET);
        seedStateFromRestOrderBooks(outcomeByTokenId);
        webSocketSubscription = webSocketClient.subscribeToMarketData(
                tokenIds,
                message -> handleMarketMessage(message, market.id(), outcomeByTokenId));
    }

    private Map<String, String> buildOutcomeMap(List<String> tokenIds, List<String> outcomes) {
        Map<String, String> result = new HashMap<>();
        int count = Math.min(tokenIds.size(), outcomes.size());
        for (int i = 0; i < count; i++) {
            result.put(tokenIds.get(i), outcomes.get(i));
        }
        return result;
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

    private void seedStateFromRestOrderBooks(Map<String, String> outcomeByTokenId) {
        outcomeByTokenId.forEach((tokenId, outcome) -> {
            try {
                var book = clobClient.getOrderBook(tokenId).block();
                if (book == null) {
                    log.warn("{}No REST order book returned for botId={} outcome={} tokenId={}{}",
                            LogColors.MARKET, botId(), outcome, tokenId, LogColors.RESET);
                    return;
                }
                latestPriceState.update(tokenId, outcome, book.bestBid().orElse(null), book.bestAsk().orElse(null));
            } catch (Exception e) {
                log.warn("{}Failed to seed REST book for botId={} outcome={} tokenId={}{}",
                        LogColors.MARKET, botId(), outcome, tokenId, LogColors.RESET, e);
            }
        });
    }

    private void handleMarketMessage(MarketWsMessageDto message, String subscriptionMarketId, Map<String, String> outcomeByTokenId) {
        if (message == null) {
            return;
        }
        if (!message.isMarketResolved() && !trackedMarketState.isCurrentMarket(subscriptionMarketId)) {
            log.debug("Ignoring late event for non-current bot market: botId={} marketId={} event={}",
                    botId(), subscriptionMarketId, message.eventType());
            return;
        }
        if (message.isBook() || message.isBestBidAsk()) {
            handlePriceMessage(message, outcomeByTokenId);
            return;
        }
        if (message.isMarketResolved()) {
            handleMarketResolved(message, subscriptionMarketId);
        }
    }

    private void handlePriceMessage(MarketWsMessageDto message, Map<String, String> outcomeByTokenId) {
        String tokenId = message.assetId();
        String outcome = outcomeByTokenId.get(tokenId);
        if (outcome == null) {
            return;
        }
        latestPriceState.update(tokenId, outcome, message.effectiveBestBid().orElse(null), message.effectiveBestAsk().orElse(null));
    }

    private void handleMarketResolved(MarketWsMessageDto message, String subscriptionMarketId) {
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        String resolvedMarketId = firstPresent(subscriptionMarketId, message.market(), market == null ? null : market.id());
        if (resolvedMarketId == null || resolvedMarketId.isBlank()) {
            log.warn("{}Received market_resolved but could not determine market id botId={}{}",
                    LogColors.TRADE, botId(), LogColors.RESET);
            return;
        }
        if (message.winningOutcome() == null || message.winningOutcome().isBlank()) {
            log.warn("{}Received market_resolved without winningOutcome botId={} marketId={}{}",
                    LogColors.TRADE, botId(), resolvedMarketId, LogColors.RESET);
            return;
        }
        marketResolutionService.resolveMarket(resolvedMarketId, message.winningAssetId(), message.winningOutcome(), "websocket");
        if (!trackedMarketState.isCurrentMarket(resolvedMarketId)) {
            disposeResolutionOnlySubscription(resolvedMarketId);
            return;
        }
        trackedMarketState.markResolved(message.winningOutcome());
        disposeWebSocketSubscription();
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

    private void disposeWebSocketSubscription() {
        if (webSocketSubscription != null && !webSocketSubscription.isDisposed()) {
            webSocketSubscription.dispose();
            log.info("Bot WebSocket subscription disposed botId={}", botId());
        }
        webSocketSubscription = null;
    }

    private void keepCurrentWebSocketForResolution(String marketId) {
        if (webSocketSubscription == null || webSocketSubscription.isDisposed()) {
            webSocketSubscription = null;
            return;
        }
        resolutionOnlySubscriptions.put(marketId, webSocketSubscription);
        webSocketSubscription = null;
    }

    private void disposeResolutionOnlySubscription(String marketId) {
        Disposable subscription = resolutionOnlySubscriptions.remove(marketId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
