package com.vokerg.voktrader;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.TrackedMarketState;
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
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private Disposable webSocketSubscription;

    @Override
    public void run(String... args) {
        log.info("PaperBotRunner started");

        GammaMarketDto market = findConfiguredMarket().block();

        if (market == null) {
            log.warn(
                    "No suitable BTC Up/Down market found for interval={} minRemaining={} maxRemaining={}",
                    marketSelectionProperties.intervalOrDefault(),
                    marketSelectionProperties.minRemaining(),
                    marketSelectionProperties.maxRemaining());
            return;
        }

        trackedMarketState.setCurrentMarket(market);

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
                "Tracking market id={} slug={} question={} endDate={} remaining={} tokenOutcomeMap={}",
                market.id(),
                market.slug(),
                market.question(),
                market.endDate(),
                remaining,
                outcomeByTokenId);

        seedStateFromRestOrderBooks(outcomeByTokenId);

        webSocketSubscription = webSocketClient.subscribeToMarketData(
                tokenIds,
                message -> handleMarketMessage(message, outcomeByTokenId));

        log.info("PaperBotRunner subscribed to market data");
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
                .next()
                .switchIfEmpty(Mono.defer(this::findConfiguredMarketFromSearchFallback));
    }

    private Mono<GammaMarketDto> findConfiguredMarketFromSearchFallback() {
        String searchQuery = searchQueryForConfiguredInterval();

        log.warn(
                "No suitable market found by deterministic slug lookup. Falling back to public-search query={}",
                searchQuery);

        return gammaClient.searchBitcoinUpDownMarkets()
                .filter(this::matchesConfiguredInterval)
                .filter(this::hasEnoughTimeRemainingForSetup)
                .next();
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
         * Future windows matter when the current market is too close to expiry.
         */
        for (int i = 0; i <= 6; i++) {
            long startEpoch = currentStartEpoch + (i * stepSeconds);
            slugs.add("btc-updown-" + interval + "-" + startEpoch);
        }

        log.info("Candidate slugs for interval={}: {}", interval, slugs);

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
                    "Skipping market too close to expiry: question={} slug={} remaining={} minRemaining={}",
                    market.question(),
                    market.slug(),
                    remaining,
                    marketSelectionProperties.minRemaining());
        }

        return hasEnoughTime;
    }

    private void seedStateFromRestOrderBooks(Map<String, String> outcomeByTokenId) {
        outcomeByTokenId.forEach((tokenId, outcome) -> {
            try {
                var book = clobClient.getOrderBook(tokenId).block();

                if (book == null) {
                    log.warn("No REST order book returned for outcome={} tokenId={}", outcome, tokenId);
                    return;
                }

                latestPriceState.update(
                        tokenId,
                        outcome,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null));

                log.info(
                        "Seeded state from REST outcome={} bid={} ask={} spread={}",
                        outcome,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null),
                        book.spread().orElse(null));
            } catch (Exception e) {
                log.warn("Failed to seed REST book for outcome={} tokenId={}", outcome, tokenId, e);
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

            return;
        }

        if (message.isMarketResolved()) {
            log.info(
                    "Market resolved winningAssetId={} winningOutcome={}",
                    message.winningAssetId(),
                    message.winningOutcome());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (webSocketSubscription != null && !webSocketSubscription.isDisposed()) {
            webSocketSubscription.dispose();
            log.info("PaperBotRunner WebSocket subscription disposed");
        }
    }
}