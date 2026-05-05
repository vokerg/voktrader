package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPriceFeedService {
    private final ClobClient clobClient;
    private final PolymarketWebSocketClient webSocketClient;
    private final MarketOrderBookMonitor orderBookMonitor;
    private final TradingEventLogger eventLogger;

    private final Map<String, MarketPriceFeed> feedsByMarketId = new ConcurrentHashMap<>();

    public synchronized MarketPriceFeedHandle acquire(
            Long botId,
            GammaMarketDto market,
            MarketTokenMap tokenMap,
            MarketResolutionListener resolutionListener
    ) {
        if (market == null || market.id() == null || market.id().isBlank()) {
            throw new IllegalArgumentException("market id is required for a price feed");
        }
        if (tokenMap == null || tokenMap.tokenIds().isEmpty()) {
            throw new IllegalArgumentException("token ids are required for a price feed");
        }

        MarketPriceFeed feed = feedsByMarketId.get(market.id());
        boolean created = false;
        if (feed == null) {
            feed = new MarketPriceFeed(market, tokenMap);
            feedsByMarketId.put(market.id(), feed);
            created = true;
        } else {
            feed.refreshMarket(market);
        }
        feed.addSubscriber(botId, resolutionListener);
        if (created) {
            feed.start();
        }
        log.info("{}Market price feed acquired marketId={} botId={} subscribers={}{}",
                LogColors.MARKET, market.id(), botId, feed.subscriberCount(), LogColors.RESET);
        return new MarketPriceFeedHandle(this, market.id(), botId, market, feed.latestPriceState());
    }

    void release(String marketId, Long botId) {
        if (marketId == null) {
            return;
        }
        feedsByMarketId.computeIfPresent(marketId, (ignored, feed) -> {
            feed.removeSubscriber(botId);
            log.info("{}Market price feed released marketId={} botId={} subscribers={}{}",
                    LogColors.MARKET, marketId, botId, feed.subscriberCount(), LogColors.RESET);
            if (feed.hasSubscribers()) {
                return feed;
            }
            feed.stop();
            return null;
        });
    }

    @PreDestroy
    public void shutdown() {
        feedsByMarketId.values().forEach(MarketPriceFeed::stop);
        feedsByMarketId.clear();
    }

    private final class MarketPriceFeed {
        private final MarketTokenMap tokenMap;
        private final LatestPriceState latestPriceState = new LatestPriceState();
        private final Map<Long, MarketResolutionListener> resolutionListenersByBotId = new ConcurrentHashMap<>();
        private final Map<String, Instant> lastPriceLogByTokenId = new ConcurrentHashMap<>();
        private volatile GammaMarketDto market;
        private volatile Disposable webSocketSubscription;

        private MarketPriceFeed(GammaMarketDto market, MarketTokenMap tokenMap) {
            this.market = market;
            this.tokenMap = tokenMap;
        }

        private void refreshMarket(GammaMarketDto market) {
            this.market = market;
        }

        private LatestPriceState latestPriceState() {
            return latestPriceState;
        }

        private void start() {
            seedStateFromRestOrderBooks();
            orderBookMonitor.beginMonitoring(market, tokenMap);
            webSocketSubscription = webSocketClient.subscribeToMarketData(
                    tokenMap.tokenIds(),
                    message -> handleMarketMessage(message, market.id())
            );
            eventLogger.market(
                    "MARKET_PRICE_FEED_STARTED",
                    null,
                    market,
                    "shared market price feed started",
                    TelemetryData.data("tokenIds", tokenMap.tokenIds()),
                    true
            );
        }

        private void addSubscriber(Long botId, MarketResolutionListener listener) {
            if (botId != null && listener != null) {
                resolutionListenersByBotId.put(botId, listener);
            }
        }

        private void removeSubscriber(Long botId) {
            if (botId != null) {
                resolutionListenersByBotId.remove(botId);
            }
        }

        private int subscriberCount() {
            return resolutionListenersByBotId.size();
        }

        private boolean hasSubscribers() {
            return !resolutionListenersByBotId.isEmpty();
        }

        private void stop() {
            if (webSocketSubscription != null && !webSocketSubscription.isDisposed()) {
                webSocketSubscription.dispose();
            }
            webSocketSubscription = null;
            latestPriceState.clear();
            orderBookMonitor.stopMonitoring(market.id());
            eventLogger.market(
                    "MARKET_PRICE_FEED_STOPPED",
                    null,
                    market,
                    "shared market price feed stopped",
                    Map.of(),
                    false
            );
        }

        private void seedStateFromRestOrderBooks() {
            tokenMap.outcomeByTokenId().forEach((tokenId, outcome) -> {
                try {
                    var book = clobClient.getOrderBook(tokenId).block();
                    if (book == null) {
                        log.warn("{}No REST order book returned for marketId={} outcome={} tokenId={}{}",
                                LogColors.MARKET, market.id(), outcome, tokenId, LogColors.RESET);
                        eventLogger.price(
                                "PRICE_SEED_MISSING_BOOK",
                                null,
                                market,
                                null,
                                "REST order book missing",
                                TelemetryData.data("outcome", outcome, "tokenId", tokenId),
                                true
                        );
                        return;
                    }
                    latestPriceState.update(tokenId, outcome, book.bestBid().orElse(null), book.bestAsk().orElse(null));
                    log.info("{}Seeded market price state marketId={} outcome={} tokenId={} bid={} ask={} spread={}{}",
                            LogColors.MARKET, market.id(), outcome, tokenId,
                            book.bestBid().orElse(null), book.bestAsk().orElse(null), book.spread().orElse(null), LogColors.RESET);
                    eventLogger.price(
                            "PRICE_SEEDED_FROM_REST",
                            null,
                            market,
                            null,
                            "seeded REST book",
                            TelemetryData.data(
                                    "outcome", outcome,
                                    "tokenId", tokenId,
                                    "bid", book.bestBid().orElse(null),
                                    "ask", book.bestAsk().orElse(null),
                                    "spread", book.spread().orElse(null)
                            ),
                            false
                    );
                } catch (Exception e) {
                    log.warn("{}Failed to seed REST book for marketId={} outcome={} tokenId={}{}",
                            LogColors.MARKET, market.id(), outcome, tokenId, LogColors.RESET, e);
                    eventLogger.price(
                            "PRICE_SEED_FAILED",
                            null,
                            market,
                            null,
                            "REST seed failed",
                            TelemetryData.data("outcome", outcome, "tokenId", tokenId, "error", e.getMessage()),
                            true
                    );
                }
            });
        }

        private void handleMarketMessage(MarketWsMessageDto message, String subscriptionMarketId) {
            if (message == null) {
                return;
            }
            if (message.isBook() || message.isBestBidAsk() || message.isPriceChange()) {
                handlePriceMessage(message);
                return;
            }
            if (message.isMarketResolved()) {
                notifyResolved(subscriptionMarketId, message);
            }
        }

        private void handlePriceMessage(MarketWsMessageDto message) {
            if (message.isPriceChange()) {
                handlePriceChangeMessage(message);
                return;
            }
            String tokenId = message.assetId();
            String outcome = tokenMap.outcomeFor(tokenId);
            if (outcome == null) {
                return;
            }
            BigDecimal bid = message.effectiveBestBid().orElse(null);
            BigDecimal ask = message.effectiveBestAsk().orElse(null);
            latestPriceState.update(tokenId, outcome, bid, ask);
            logPriceUpdate(message.eventType(), tokenId, outcome, bid, ask);
        }

        private void handlePriceChangeMessage(MarketWsMessageDto message) {
            if (message.priceChanges() == null || message.priceChanges().isEmpty()) {
                return;
            }
            message.priceChanges().forEach(change -> {
                String tokenId = change.assetId();
                String outcome = tokenMap.outcomeFor(tokenId);
                if (outcome == null) {
                    return;
                }
                BigDecimal bid = parseDecimal(change.bestBid()).orElse(null);
                BigDecimal ask = parseDecimal(change.bestAsk()).orElse(null);
                latestPriceState.update(tokenId, outcome, bid, ask);
                logPriceUpdate(message.eventType(), tokenId, outcome, bid, ask);
            });
        }

        private void logPriceUpdate(String eventType, String tokenId, String outcome, BigDecimal bid, BigDecimal ask) {
            Instant now = Instant.now();
            Instant lastLoggedAt = lastPriceLogByTokenId.get(tokenId);
            if (lastLoggedAt != null && Duration.between(lastLoggedAt, now).compareTo(Duration.ofSeconds(10)) < 0) {
                return;
            }
            lastPriceLogByTokenId.put(tokenId, now);
            log.info("{}Live market price update marketId={} event={} outcome={} tokenId={} bid={} ask={} spread={}{}",
                    LogColors.SNAPSHOT, market.id(), eventType, outcome, tokenId, bid, ask,
                    ask != null && bid != null ? ask.subtract(bid) : null, LogColors.RESET);
            eventLogger.price(
                    "PRICE_WS_UPDATE",
                    null,
                    market,
                    null,
                    "websocket price update",
                    TelemetryData.data(
                            "eventType", eventType,
                            "outcome", outcome,
                            "tokenId", tokenId,
                            "bid", bid,
                            "ask", ask,
                            "spread", ask != null && bid != null ? ask.subtract(bid) : null
                    ),
                    false
            );
        }

        private void notifyResolved(String subscriptionMarketId, MarketWsMessageDto message) {
            for (MarketResolutionListener listener : resolutionListenersByBotId.values()) {
                listener.onMarketResolved(subscriptionMarketId, message);
            }
            feedsByMarketId.remove(market.id(), this);
            stop();
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
    }
}
