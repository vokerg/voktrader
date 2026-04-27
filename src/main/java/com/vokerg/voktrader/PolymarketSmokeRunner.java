package com.vokerg.voktrader;

import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CountDownLatch;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketSmokeRunner implements CommandLineRunner {

    private final GammaClient gammaClient;
    private final ClobClient clobClient;
    private final PolymarketWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        var market = gammaClient.searchBitcoinUpDownMarkets()
                .next()
                .onErrorResume(e -> {
                    log.error("Smoke test failed while finding market", e);
                    return reactor.core.publisher.Mono.empty();
                })
                .block();

        if (market == null) {
            log.warn("No active Bitcoin markets found in scanned pages");
            return;
        }

        var tokenIds = market.tokenIds(objectMapper);

        log.info("Using market: {}", market.question());
        log.info("Slug: {}", market.slug());
        log.info("Token IDs: {}", tokenIds);
        log.info("Outcomes: {}", market.outcomeNames(objectMapper));

        for (String tokenId : tokenIds) {
            var book = clobClient.getOrderBook(tokenId).block();

            if (book != null) {
                log.info(
                        "Book token={} bestBid={} bestAsk={} spread={} lastTrade={}",
                        tokenId,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null),
                        book.spread().orElse(null),
                        book.lastTradePrice());
            }
        }

        CountDownLatch latch = new CountDownLatch(1);

        webSocketClient.subscribeToMarketData(tokenIds, message -> {
            log.info(
                    "WS event={} market={} asset={} bid={} ask={} spread={} winningOutcome={}",
                    message.eventType(),
                    message.market(),
                    message.assetId(),
                    message.effectiveBestBid().orElse(null),
                    message.effectiveBestAsk().orElse(null),
                    message.effectiveSpread().orElse(null),
                    message.winningOutcome());
        });

        latch.await();
    }

}
