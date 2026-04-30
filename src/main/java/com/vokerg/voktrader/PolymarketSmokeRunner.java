package com.vokerg.voktrader;

import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketSmokeRunner {// implements CommandLineRunner {

    private final GammaClient gammaClient;
    private final ClobClient clobClient;
    private final PolymarketWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;

    private Disposable wsSubscription;

    //@Override
    public void run(String... args) {
        var market = gammaClient.searchBitcoinUpDownMarkets()
                .next()
                .onErrorResume(e -> {
                    log.error("Smoke test failed while finding market", e);
                    return reactor.core.publisher.Mono.empty();
                })
                .block();

        if (market == null) {
            log.warn("No active Bitcoin Up/Down markets found");
            return;
        }

        var tokenIds = market.tokenIds(objectMapper);
        var outcomes = market.outcomeNames(objectMapper);

        Map<String, String> tokenToOutcome = buildTokenToOutcomeMap(tokenIds, outcomes);

        log.info("========================================");
        log.info("POLYMARKET SMOKE TEST");
        log.info("Market: {}", market.question());
        log.info("Slug: {}", market.slug());
        log.info("End date: {}", market.endDate());
        log.info("Accepting orders: {}", market.acceptingOrders());
        log.info("Token mapping:");

        tokenToOutcome.forEach((tokenId, outcome) ->
                log.info("  {} -> {}", outcome, tokenId)
        );

        log.info("========================================");

        fetchInitialOrderBooks(tokenIds, tokenToOutcome);

        wsSubscription = webSocketClient.subscribeToMarketData(tokenIds, message -> {
            switch (message.eventType()) {
                case "new_market" -> {
                    // Global notification, not relevant to our selected market right now.
                }

                case "last_trade_price" -> {
                    // Useful later, but not needed for the paper-signal MVP.
                }

                case "price_change" -> {
                    // Very noisy. Ignore for now.
                    // Later this can feed LatestPriceState if we want every tiny update.
                }

                case "book", "best_bid_ask" -> {
                    String outcome = tokenToOutcome.getOrDefault(message.assetId(), "UNKNOWN");

                    log.info(
                            "WS {} outcome={} bid={} ask={} spread={}",
                            message.eventType(),
                            outcome,
                            message.effectiveBestBid().orElse(null),
                            message.effectiveBestAsk().orElse(null),
                            message.effectiveSpread().orElse(null)
                    );
                }

                case "market_resolved" -> {
                    log.info(
                            "MARKET RESOLVED winningAssetId={} winningOutcome={}",
                            message.winningAssetId(),
                            message.winningOutcome()
                    );
                }

                default -> log.debug(
                        "Ignored WS event={} market={} asset={}",
                        message.eventType(),
                        message.market(),
                        message.assetId()
                );
            }
        });
    }

    private Map<String, String> buildTokenToOutcomeMap(
            java.util.List<String> tokenIds,
            java.util.List<String> outcomes
    ) {
        Map<String, String> tokenToOutcome = new HashMap<>();

        for (int i = 0; i < tokenIds.size(); i++) {
            String outcome = i < outcomes.size() ? outcomes.get(i) : "UNKNOWN";
            tokenToOutcome.put(tokenIds.get(i), outcome);
        }

        return tokenToOutcome;
    }

    private void fetchInitialOrderBooks(
            java.util.List<String> tokenIds,
            Map<String, String> tokenToOutcome
    ) {
        for (String tokenId : tokenIds) {
            var book = clobClient.getOrderBook(tokenId).block();

            if (book == null) {
                log.warn("No order book returned for token={}", tokenId);
                continue;
            }

            log.info(
                    "REST book outcome={} token={} bid={} ask={} spread={} lastTrade={}",
                    tokenToOutcome.getOrDefault(tokenId, "UNKNOWN"),
                    tokenId,
                    book.bestBid().orElse(null),
                    book.bestAsk().orElse(null),
                    book.spread().orElse(null),
                    book.lastTradePrice()
            );
        }
    }
}
