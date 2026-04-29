package com.vokerg.voktrader;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.MarketPersistenceService;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.paper.FakeSignalService;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaperBotRunner implements CommandLineRunner {

    private final GammaClient gammaClient;
    private final ClobClient clobClient;
    private final PolymarketWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final LatestPriceState latestPriceState;
    private final MarketSelectionProperties marketSelectionProperties;
    private final TrackedMarketState trackedMarketState;
    private final MarketPersistenceService marketPersistenceService;
    private final FakeSignalService fakeSignalService;

    private final AtomicBoolean rolloverInProgress = new AtomicBoolean(false);

    private Disposable webSocketSubscription;

    @Override
    public void run(String... args) {
        log.info("PaperBotRunner started");
        rollToNextMarket("startup");
    }

    /**
     * Safety net.
     *
     * Normal rollover happens immediately from the market_resolved WS event.
     * This scheduled method is here so the bot can recover if:
     * - startup finds no market yet,
     * - a rollover search temporarily finds nothing,
     * - a WS message is missed,
     * - the subscription dies and the current market is already marked resolved.
     */
    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    public void ensureMarketIsTracked() {
        if (trackedMarketState.currentMarket().isEmpty()) {
            rollToNextMarket("no current market");
            return;
        }

        if (trackedMarketState.isResolved()) {
            rollToNextMarket("current market resolved");
        }
    }

    private void rollToNextMarket(String reason) {
        if (!rolloverInProgress.compareAndSet(false, true)) {
            log.debug("Rollover already in progress, skipping reason={}", reason);
            return;
        }

        log.info("{}Looking for next market, reason={}{}", LogColors.MARKET, reason, LogColors.RESET);

        AtomicBoolean foundMarket = new AtomicBoolean(false);

        findConfiguredMarket()
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(market -> Mono.fromCallable(() -> {
                    foundMarket.set(true);

                    /*
                     * This contains blocking calls through seedStateFromRestOrderBooks(...).
                     * Force it onto boundedElastic no matter which thread discovered the market.
                     */
                    trackMarket(market);

                    return market;
                })
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(error -> log.error("Failed during market rollover", error))
                .doFinally(signalType -> rolloverInProgress.set(false))
                .subscribe(
                        market -> log.info(
                                "{}Rollover complete: now tracking marketId={} slug={} question={}{}",
                                LogColors.MARKET,
                                market.id(),
                                market.slug(),
                                market.question(),
                                LogColors.RESET),
                        error -> log.error("Rollover subscription failed", error),
                        () -> {
                            if (!foundMarket.get()) {
                                log.warn(
                                        "{}No next market found during rollover, reason={}{}",
                                        LogColors.MARKET,
                                        reason,
                                        LogColors.RESET);
                            }
                        });
    }

    private void trackMarket(GammaMarketDto market) {
        if (market == null) {
            return;
        }

        disposeWebSocketSubscription();

        /*
         * Important for rollover:
         * Do not let old Up/Down prices leak into the new market.
         */
        latestPriceState.clear();

        trackedMarketState.startTracking(market);
        var savedMarket = marketPersistenceService.saveOrUpdate(market);

        List<String> tokenIds = market.tokenIds(objectMapper);
        List<String> outcomes = market.outcomeNames(objectMapper);

        if (tokenIds.size() < 2 || outcomes.size() < 2) {
            log.warn(
                    "Market did not have enough token/outcome data: question={} tokenIds={} outcomes={}",
                    market.question(),
                    tokenIds,
                    outcomes);
            return;
        }

        Map<String, String> outcomeByTokenId = buildOutcomeMap(tokenIds, outcomes);

        Duration remaining = market.endDate() == null
                ? null
                : Duration.between(Instant.now(), market.endDate());

        log.info(
                "{}Tracking market id={} slug={} question={} endDate={} remaining={} tokenOutcomeMap={}{}",
                LogColors.MARKET,
                market.id(),
                market.slug(),
                market.question(),
                market.endDate(),
                remaining,
                outcomeByTokenId,
                LogColors.RESET);
        log.info(
                "{}Persisted tracked market dbId={} polymarketMarketId={}{}",
                LogColors.MARKET,
                savedMarket.getId(),
                savedMarket.getPolymarketMarketId(),
                LogColors.RESET);

        seedStateFromRestOrderBooks(outcomeByTokenId);

        webSocketSubscription = webSocketClient.subscribeToMarketData(
                tokenIds,
                message -> handleMarketMessage(message, outcomeByTokenId));

        log.info(
                "{}PaperBotRunner subscribed to market data for marketId={} slug={}{}",
                LogColors.MARKET,
                market.id(),
                market.slug(),
                LogColors.RESET);
    }

    private Mono<GammaMarketDto> findConfiguredMarket() {
        String interval = marketSelectionProperties.intervalOrDefault();

        return Flux.fromIterable(candidateSlugs(interval))
                .concatMap(slug -> gammaClient.getMarketBySlug(slug)
                        .onErrorResume(error -> {
                            log.debug("Failed slug lookup for slug={}", slug, error);
                            return Mono.empty();
                        }))
                .filter(GammaMarketDto::isActiveOpenMarket)
                .filter(GammaMarketDto::acceptsOrders)
                .filter(market -> market.endsAfter(Instant.now()))
                .filter(this::matchesConfiguredInterval)
                .filter(this::hasEnoughTimeRemainingForSetup)
                .filter(this::isNotAlreadyResolvedCurrentMarket)
                .next()
                .switchIfEmpty(Mono.defer(this::findConfiguredMarketFromSearchFallback));
    }

    private Mono<GammaMarketDto> findConfiguredMarketFromSearchFallback() {
        String searchQuery = searchQueryForConfiguredInterval();

        log.warn(
                "{}No suitable market found by deterministic slug lookup. Falling back to public-search query={}{}",
                LogColors.MARKET,
                searchQuery,
                LogColors.RESET);

        /*
         * Current GammaClient in the public repo exposes searchBitcoinUpDownMarkets()
         * with no query argument, so keep this call simple.
         */
        return gammaClient.searchBitcoinUpDownMarkets()
                .filter(this::matchesConfiguredInterval)
                .filter(this::hasEnoughTimeRemainingForSetup)
                .filter(this::isNotAlreadyResolvedCurrentMarket)
                .next();
    }

    private boolean isNotAlreadyResolvedCurrentMarket(GammaMarketDto candidate) {
        if (candidate == null) {
            return false;
        }

        if (!trackedMarketState.isResolved()) {
            return true;
        }

        GammaMarketDto current = trackedMarketState.currentMarket().orElse(null);

        if (current == null) {
            return true;
        }

        boolean sameId = current.id() != null && current.id().equals(candidate.id());
        boolean sameSlug = current.slug() != null && current.slug().equals(candidate.slug());

        if (sameId || sameSlug) {
            log.info(
                    "{}Skipping already resolved current market during rollover: id={} slug={}{}",
                    LogColors.MARKET,
                    candidate.id(),
                    candidate.slug(),
                    LogColors.RESET);
            return false;
        }

        return true;
    }

    private List<String> candidateSlugs(String interval) {
        long stepSeconds = intervalStepSeconds(interval);
        long nowEpoch = Instant.now().getEpochSecond();
        long currentStartEpoch = (nowEpoch / stepSeconds) * stepSeconds;

        List<String> slugs = new ArrayList<>();

        /*
         * Current window plus next few windows.
         *
         * Current matters when the market is already live.
         * Future windows matter when the current market is too close to expiry
         * or has just resolved.
         */
        for (int i = 0; i <= 6; i++) {
            long startEpoch = currentStartEpoch + (i * stepSeconds);
            slugs.add("btc-updown-" + interval + "-" + startEpoch);
        }

        log.info("{}Candidate slugs for interval={}: {}{}", LogColors.MARKET, interval, slugs, LogColors.RESET);

        return slugs;
    }

    private long intervalStepSeconds(String interval) {
        return switch (interval) {
            case "5m" -> 5 * 60L;
            case "15m" -> 15 * 60L;
            case "4h" -> 4 * 60 * 60L;
            default -> throw new IllegalArgumentException("Unsupported interval: " + interval);
        };
    }

    private String searchQueryForConfiguredInterval() {
        return switch (marketSelectionProperties.intervalOrDefault()) {
            case "15m" -> "btc updown 15m";
            case "5m" -> "btc updown 5m";
            case "4h" -> "btc updown 4h";
            default -> "bitcoin up or down";
        };
    }

    private Map<String, String> buildOutcomeMap(List<String> tokenIds, List<String> outcomes) {
        Map<String, String> result = new HashMap<>();

        int count = Math.min(tokenIds.size(), outcomes.size());

        for (int i = 0; i < count; i++) {
            result.put(tokenIds.get(i), outcomes.get(i));
        }

        return result;
    }

    private boolean matchesConfiguredInterval(GammaMarketDto market) {
        String interval = marketSelectionProperties.intervalOrDefault();
        String slug = market.slug() == null ? "" : market.slug().toLowerCase();

        boolean matches = slug.contains("updown-" + interval + "-");

        if (!matches) {
            log.debug(
                    "Skipping market because interval does not match: interval={} slug={} question={}",
                    interval,
                    market.slug(),
                    market.question());
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
            log.info(
                    "{}Skipping market too close to expiry: question={} slug={} remaining={} minRemaining={}{}",
                    LogColors.MARKET,
                    market.question(),
                    market.slug(),
                    remaining,
                    marketSelectionProperties.minRemaining(),
                    LogColors.RESET);
        }

        return hasEnoughTime;
    }

    private void seedStateFromRestOrderBooks(Map<String, String> outcomeByTokenId) {
        outcomeByTokenId.forEach((tokenId, outcome) -> {
            try {
                var book = clobClient.getOrderBook(tokenId).block();

                if (book == null) {
                    log.warn(
                            "{}No REST order book returned for outcome={} tokenId={}{}",
                            LogColors.MARKET,
                            outcome,
                            tokenId,
                            LogColors.RESET);
                    return;
                }

                latestPriceState.update(
                        tokenId,
                        outcome,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null));

                log.info(
                        "{}Seeded state from REST outcome={} bid={} ask={} spread={}{}",
                        LogColors.MARKET,
                        outcome,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null),
                        book.spread().orElse(null),
                        LogColors.RESET);
            } catch (Exception e) {
                log.warn(
                        "{}Failed to seed REST book for outcome={} tokenId={}{}",
                        LogColors.MARKET,
                        outcome,
                        tokenId,
                        LogColors.RESET,
                        e);
            }
        });
    }

    private void handleMarketMessage(
            MarketWsMessageDto message,
            Map<String, String> outcomeByTokenId) {
        if (message == null) {
            return;
        }

        if (message.isBook() || message.isBestBidAsk()) {
            handlePriceMessage(message, outcomeByTokenId);
            return;
        }

        if (message.isMarketResolved()) {
            handleMarketResolved(message);
        }
    }

    private void handlePriceMessage(
            MarketWsMessageDto message,
            Map<String, String> outcomeByTokenId) {
        String tokenId = message.assetId();
        String outcome = outcomeByTokenId.get(tokenId);

        if (outcome == null) {
            log.debug("Ignoring WS message for unknown tokenId={}", tokenId);
            return;
        }

        latestPriceState.update(
                tokenId,
                outcome,
                message.effectiveBestBid().orElse(null),
                message.effectiveBestAsk().orElse(null));

        log.debug(
                "Updated state from WS event={} outcome={} bid={} ask={} spread={}",
                message.eventType(),
                outcome,
                message.effectiveBestBid().orElse(null),
                message.effectiveBestAsk().orElse(null),
                message.effectiveSpread().orElse(null));
    }

    private void handleMarketResolved(MarketWsMessageDto message) {
        log.info(
                "{}Market resolved winningAssetId={} winningOutcome={}{}",
                LogColors.TRADE,
                message.winningAssetId(),
                message.winningOutcome(),
                LogColors.RESET);

        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);

        if (market == null) {
            log.warn(
                    "{}Received market_resolved but no current market is tracked{}",
                    LogColors.TRADE,
                    LogColors.RESET);
            return;
        }

        if (trackedMarketState.isResolved()) {
            log.info(
                    "{}Ignoring duplicate market_resolved for marketId={} slug={}{}",
                    LogColors.TRADE,
                    market.id(),
                    market.slug(),
                    LogColors.RESET);
            return;
        }

        if (message.winningOutcome() == null || message.winningOutcome().isBlank()) {
            log.warn(
                    "{}Received market_resolved without winningOutcome for marketId={} slug={}{}",
                    LogColors.TRADE,
                    market.id(),
                    market.slug(),
                    LogColors.RESET);
            return;
        }

        trackedMarketState.markResolved(message.winningOutcome());

        marketPersistenceService.markResolved(
                market.id(),
                message.winningOutcome(),
                message.winningAssetId());

        fakeSignalService.resolveMarket(
                market.id(),
                message.winningOutcome());

        disposeWebSocketSubscription();

        rollToNextMarket("market_resolved");
    }

    private void disposeWebSocketSubscription() {
        if (webSocketSubscription != null && !webSocketSubscription.isDisposed()) {
            webSocketSubscription.dispose();
            log.info("PaperBotRunner WebSocket subscription disposed");
        }

        webSocketSubscription = null;
    }

    @PreDestroy
    public void shutdown() {
        disposeWebSocketSubscription();
    }
}
