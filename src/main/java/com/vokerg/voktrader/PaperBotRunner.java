package com.vokerg.voktrader;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
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

        GammaMarketDto market = gammaClient.searchBitcoinUpDownMarkets()
                .filter(this::matchesConfiguredInterval)
                .filter(this::hasEnoughTimeRemainingForSetup)
                .next()
                .block();

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

        log.info(
                "Tracking market id={} question={} endDate={} tokenOutcomeMap={}",
                market.id(),
                market.question(),
                market.endDate(),
                outcomeByTokenId);

        seedStateFromRestOrderBooks(outcomeByTokenId);

        webSocketSubscription = webSocketClient.subscribeToMarketData(
                tokenIds,
                message -> handleMarketMessage(message, outcomeByTokenId));

        log.info("PaperBotRunner subscribed to market data");
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
        String question = market.question() == null ? "" : market.question().toLowerCase();

        boolean matchesSlug = slug.contains("-" + interval + "-");
        boolean matchesQuestion = question.contains(interval);

        boolean matches = matchesSlug || matchesQuestion;

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
                    "Skipping market too close to expiry: question={} remaining={} minRemaining={}",
                    market.question(),
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
}
