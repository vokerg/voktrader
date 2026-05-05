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
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final TradingEventLogger eventLogger;
    private final AtomicBoolean rolloverInProgress = new AtomicBoolean(false);
    private final Map<String, Disposable> resolutionOnlySubscriptions = new ConcurrentHashMap<>();
    private final LatestPriceState latestPriceState = new LatestPriceState();
    private final TrackedMarketState trackedMarketState = new TrackedMarketState();
    private final Map<String, Instant> lastPriceLogByTokenId = new ConcurrentHashMap<>();
    private Disposable webSocketSubscription;

    public BotRuntime(
            BotConfigEntity config,
            GammaClient gammaClient,
            ClobClient clobClient,
            PolymarketWebSocketClient webSocketClient,
            ObjectMapper objectMapper,
            MarketSelectionProperties marketSelectionProperties,
            MarketPersistenceService marketPersistenceService,
            MarketResolutionService marketResolutionService,
            TradingEventLogger eventLogger
    ) {
        this.config = config;
        this.gammaClient = gammaClient;
        this.clobClient = clobClient;
        this.webSocketClient = webSocketClient;
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
        return new BotRuntimeContext(botId(), config.getMarketFamily(), config.getStrategyId(), trackedMarketState, latestPriceState);
    }

    public void start(String reason) {
        log.info("{}Starting bot runtime id={} name={} family={} strategy={} reason={}{}",
                LogColors.MARKET, botId(), config.getName(), config.getMarketFamily(), config.getStrategyId(), reason, LogColors.RESET);
        eventLogger.market(
                "BOT_RUNTIME_STARTED",
                botId(),
                null,
                reason,
                TelemetryData.data("name", config.getName(), "family", config.getMarketFamily(), "strategyId", config.getStrategyId()),
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
        disposeWebSocketSubscription();
        latestPriceState.clear();
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
        Map<String, String> outcomeByTokenId = buildOutcomeMap(tokenIds, outcomes);
        Duration remaining = market.endDate() == null ? null : Duration.between(Instant.now(), market.endDate());
        log.info("{}Tracking bot market botId={} marketId={} dbId={} slug={} family={} endDate={} remaining={} tokenOutcomeMap={}{}",
                LogColors.MARKET, botId(), market.id(), savedMarket.getId(), market.slug(), config.getMarketFamily(),
                market.endDate(), remaining, outcomeByTokenId, LogColors.RESET);
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
                        "outcomeByTokenId", outcomeByTokenId
                ),
                true
        );
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
                    eventLogger.price(
                            "PRICE_SEED_MISSING_BOOK",
                            botId(),
                            trackedMarketState.currentMarket().orElse(null),
                            null,
                            "REST order book missing",
                            TelemetryData.data("outcome", outcome, "tokenId", tokenId),
                            true
                    );
                    return;
                }
                latestPriceState.update(tokenId, outcome, book.bestBid().orElse(null), book.bestAsk().orElse(null));
                log.info("{}Seeded bot price state botId={} outcome={} tokenId={} bid={} ask={} spread={}{}",
                        LogColors.MARKET, botId(), outcome, tokenId,
                        book.bestBid().orElse(null), book.bestAsk().orElse(null), book.spread().orElse(null), LogColors.RESET);
                eventLogger.price(
                        "PRICE_SEEDED_FROM_REST",
                        botId(),
                        trackedMarketState.currentMarket().orElse(null),
                        null,
                        "seeded REST book",
                        TelemetryData.data(
                                "outcome", outcome,
                                "tokenId", tokenId,
                                "bid", book.bestBid().orElse(null),
                                "ask", book.bestAsk().orElse(null),
                                "spread", book.spread().orElse(null)
                        ),
                        true
                );
            } catch (Exception e) {
                log.warn("{}Failed to seed REST book for botId={} outcome={} tokenId={}{}",
                        LogColors.MARKET, botId(), outcome, tokenId, LogColors.RESET, e);
                eventLogger.price(
                        "PRICE_SEED_FAILED",
                        botId(),
                        trackedMarketState.currentMarket().orElse(null),
                        null,
                        "REST seed failed",
                        TelemetryData.data("outcome", outcome, "tokenId", tokenId, "error", e.getMessage()),
                        true
                );
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
            eventLogger.market(
                    "MARKET_LATE_WS_EVENT_IGNORED",
                    botId(),
                    trackedMarketState.currentMarket().orElse(null),
                    "late websocket event",
                    TelemetryData.data("subscriptionMarketId", subscriptionMarketId, "eventType", message.eventType()),
                    false
            );
            return;
        }
        if (message.isBook() || message.isBestBidAsk() || message.isPriceChange()) {
            handlePriceMessage(message, outcomeByTokenId);
            return;
        }
        if (message.isMarketResolved()) {
            handleMarketResolved(message, subscriptionMarketId);
        }
    }

    private void handlePriceMessage(MarketWsMessageDto message, Map<String, String> outcomeByTokenId) {
        if (message.isPriceChange()) {
            handlePriceChangeMessage(message, outcomeByTokenId);
            return;
        }
        String tokenId = message.assetId();
        String outcome = outcomeByTokenId.get(tokenId);
        if (outcome == null) {
            return;
        }
        var bid = message.effectiveBestBid().orElse(null);
        var ask = message.effectiveBestAsk().orElse(null);
        latestPriceState.update(tokenId, outcome, bid, ask);
        Instant now = Instant.now();
        Instant lastLoggedAt = lastPriceLogByTokenId.get(tokenId);
        if (lastLoggedAt == null || Duration.between(lastLoggedAt, now).compareTo(Duration.ofSeconds(10)) >= 0) {
            lastPriceLogByTokenId.put(tokenId, now);
            log.info("{}Live bot price update botId={} event={} outcome={} tokenId={} bid={} ask={} spread={}{}",
                    LogColors.SNAPSHOT, botId(), message.eventType(), outcome, tokenId, bid, ask,
                    ask != null && bid != null ? ask.subtract(bid) : null, LogColors.RESET);
            eventLogger.price(
                    "PRICE_WS_UPDATE",
                    botId(),
                    trackedMarketState.currentMarket().orElse(null),
                    null,
                    "websocket price update",
                    TelemetryData.data(
                            "eventType", message.eventType(),
                            "outcome", outcome,
                            "tokenId", tokenId,
                            "bid", bid,
                            "ask", ask,
                            "spread", ask != null && bid != null ? ask.subtract(bid) : null
                    ),
                    true
            );
        }
    }

    private void handlePriceChangeMessage(MarketWsMessageDto message, Map<String, String> outcomeByTokenId) {
        if (message.priceChanges() == null || message.priceChanges().isEmpty()) {
            return;
        }
        message.priceChanges().forEach(change -> {
            String tokenId = change.assetId();
            String outcome = outcomeByTokenId.get(tokenId);
            if (outcome == null) {
                return;
            }
            BigDecimal bid = parseDecimal(change.bestBid()).orElse(null);
            BigDecimal ask = parseDecimal(change.bestAsk()).orElse(null);
            latestPriceState.update(tokenId, outcome, bid, ask);
        });
    }

    private Optional<BigDecimal> parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void handleMarketResolved(MarketWsMessageDto message, String subscriptionMarketId) {
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
            eventLogger.market(
                    "MARKET_WS_DISPOSED",
                    botId(),
                    trackedMarketState.currentMarket().orElse(null),
                    "subscription disposed",
                    Map.of(),
                    false
            );
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
        log.info("{}Keeping expired bot market WebSocket alive for resolution only: botId={} marketId={}{}",
                LogColors.MARKET, botId(), marketId, LogColors.RESET);
        eventLogger.market(
                "MARKET_WS_RESOLUTION_ONLY",
                botId(),
                null,
                "keeping expired market websocket for resolution",
                TelemetryData.data("marketId", marketId),
                true
        );
    }

    private void disposeResolutionOnlySubscription(String marketId) {
        Disposable subscription = resolutionOnlySubscriptions.remove(marketId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            eventLogger.market(
                    "MARKET_RESOLUTION_WS_DISPOSED",
                    botId(),
                    null,
                    "resolution-only websocket disposed",
                    TelemetryData.data("marketId", marketId),
                    false
            );
        }
    }
}
