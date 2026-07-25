package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.MarketWebSocketObserver;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
    private final PriceSnapshotService priceSnapshotService;
    private final MarketDepthSnapshotService marketDepthSnapshotService;
    private final TradingEventLogger eventLogger;

    private final Map<String, MarketPriceFeed> feedsByMarketId = new ConcurrentHashMap<>();

    @Value("${voktrader.market-data.log-live-updates:false}")
    private boolean logLiveUpdates;

    @Value("${voktrader.market-data.websocket.heartbeat-timeout-ms:25000}")
    private long heartbeatTimeoutMs;

    @Value("${voktrader.market-data.websocket.max-event-gap-ms:300000}")
    private long maxEventGapMs;

    @Value("${voktrader.market-data.websocket.reseed-retry-ms:2000}")
    private long reseedRetryMs;

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
        return new MarketPriceFeedHandle(this, market.id(), botId, market, feed.latestPriceState(), feed.orderBookState());
    }

    public Optional<MarketStreamSupervisor.Snapshot> supervisionSnapshot(String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return Optional.empty();
        }
        MarketPriceFeed feed = feedsByMarketId.get(marketId);
        return feed == null ? Optional.empty() : Optional.of(feed.supervisionSnapshot());
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

    @org.springframework.scheduling.annotation.Scheduled(fixedRateString = "${voktrader.market-data.snapshot-ms:2000}")
    public void snapshotFeeds() {
        feedsByMarketId.values().forEach(MarketPriceFeed::snapshot);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${voktrader.market-data.websocket.supervision-ms:1000}")
    public void superviseFeeds() {
        Instant now = Instant.now();
        feedsByMarketId.values().forEach(feed -> feed.supervise(now));
    }

    private final class MarketPriceFeed {
        private final MarketTokenMap tokenMap;
        private final MarketStreamSupervisor supervisor;
        private final LatestPriceState latestPriceState;
        private final OrderBookState orderBookState;
        private final Map<Long, MarketResolutionListener> resolutionListenersByBotId = new ConcurrentHashMap<>();
        private final Map<String, Instant> lastPriceLogByTokenId = new ConcurrentHashMap<>();
        private volatile GammaMarketDto market;
        private volatile Disposable webSocketSubscription;
        private volatile FeedSocketObserver activeObserver;
        private volatile Instant lastReseedAttemptAt;
        private volatile boolean stopped;

        private MarketPriceFeed(GammaMarketDto market, MarketTokenMap tokenMap) {
            this.market = market;
            this.tokenMap = tokenMap;
            this.supervisor = new MarketStreamSupervisor(
                    Duration.ofMillis(positiveOrDefault(heartbeatTimeoutMs, 25_000L)),
                    Duration.ofMillis(positiveOrDefault(maxEventGapMs, 300_000L))
            );
            this.latestPriceState = new LatestPriceState(supervisor::strategyReadable);
            this.orderBookState = new OrderBookState(supervisor::strategyReadable);
        }

        private void refreshMarket(GammaMarketDto market) {
            this.market = market;
        }

        private LatestPriceState latestPriceState() {
            return latestPriceState;
        }

        private OrderBookState orderBookState() {
            return orderBookState;
        }

        private MarketStreamSupervisor.Snapshot supervisionSnapshot() {
            return supervisor.snapshot();
        }

        private void start() {
            seedStateFromRestOrderBooks(false);
            supervisor.markInitialSeedAttempted(Instant.now());
            orderBookMonitor.beginMonitoring(market, tokenMap);
            connectWebSocket();
            eventLogger.market(
                    "MARKET_PRICE_FEED_STARTED",
                    null,
                    market,
                    "shared market price feed started",
                    TelemetryData.data(
                            "tokenIds", tokenMap.tokenIds(),
                            "heartbeatTimeoutMs", heartbeatTimeoutMs,
                            "maxEventGapMs", maxEventGapMs
                    ),
                    true
            );
        }

        private synchronized void connectWebSocket() {
            if (stopped) {
                return;
            }
            FeedSocketObserver observer = new FeedSocketObserver();
            activeObserver = observer;
            webSocketSubscription = webSocketClient.subscribeToMarketData(tokenMap.tokenIds(), observer);
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

        private synchronized void stop() {
            stopped = true;
            activeObserver = null;
            if (webSocketSubscription != null && !webSocketSubscription.isDisposed()) {
                webSocketSubscription.dispose();
            }
            webSocketSubscription = null;
            supervisor.stop();
            latestPriceState.clear();
            orderBookState.clear();
            orderBookMonitor.stopMonitoring(market.id());
            eventLogger.market(
                    "MARKET_PRICE_FEED_STOPPED",
                    null,
                    market,
                    "shared market price feed stopped",
                    supervisionData(supervisor.snapshot()),
                    false
            );
        }

        private void supervise(Instant now) {
            if (stopped) {
                return;
            }
            MarketStreamSupervisor.PollAction action = supervisor.poll(now);
            if (action == MarketStreamSupervisor.PollAction.RECONNECT_STALE_STREAM) {
                emitSupervision("MARKET_WS_STALE", "websocket heartbeat timed out", true);
                restartWebSocket("HEARTBEAT_TIMEOUT");
                return;
            }
            if (action == MarketStreamSupervisor.PollAction.RESEED) {
                attemptStrictReseed(now);
            }
        }

        private synchronized void restartWebSocket(String reason) {
            if (stopped) {
                return;
            }
            supervisor.onReconnectStarted(Instant.now(), reason);
            latestPriceState.clear();
            orderBookState.clear();
            activeObserver = null;
            if (webSocketSubscription != null && !webSocketSubscription.isDisposed()) {
                webSocketSubscription.dispose();
            }
            webSocketSubscription = null;
            connectWebSocket();
        }

        private void attemptStrictReseed(Instant now) {
            Instant previousAttempt = lastReseedAttemptAt;
            if (previousAttempt != null
                    && Duration.between(previousAttempt, now).compareTo(Duration.ofMillis(positiveOrDefault(reseedRetryMs, 2_000L))) < 0) {
                return;
            }
            lastReseedAttemptAt = now;
            if (!seedStateFromRestOrderBooks(true)) {
                emitSupervision("MARKET_WS_RESEED_FAILED", "REST reseed incomplete; strategy remains paused", true);
                return;
            }
            supervisor.onReseedSucceeded(Instant.now());
            emitSupervision("MARKET_WS_RESEED_COMPLETED", "REST reseed complete; strategy data resumed", true);
        }

        private void snapshot() {
            OutcomePrice up = latestPriceState.byOutcome("Up").orElse(null);
            OutcomePrice down = latestPriceState.byOutcome("Down").orElse(null);
            if (up == null || down == null) {
                return;
            }

            Instant capturedAt = Instant.now();
            Duration remainingDuration = market.endDate() == null
                    ? null
                    : Duration.between(capturedAt, market.endDate());
            String remaining = formatRemaining(remainingDuration);

            log.info(
                    "{}SNAPSHOT scope=market marketId={} remaining={} | Up {}/{} spread={} | Down {}/{} spread={}{}",
                    LogColors.SNAPSHOT,
                    market.id(),
                    remaining,
                    up.bid(),
                    up.ask(),
                    up.spread(),
                    down.bid(),
                    down.ask(),
                    down.spread(),
                    LogColors.RESET
            );
            eventLogger.market(
                    "PRICE_SNAPSHOT",
                    null,
                    market,
                    "scheduled market feed snapshot",
                    TelemetryData.data(
                            "scope", "market",
                            "remaining", remaining,
                            "remainingSeconds", remainingDuration == null ? null : remainingDuration.getSeconds(),
                            "upTokenId", up.tokenId(),
                            "upBid", up.bid(),
                            "upAsk", up.ask(),
                            "upSpread", up.spread(),
                            "downTokenId", down.tokenId(),
                            "downBid", down.bid(),
                            "downAsk", down.ask(),
                            "downSpread", down.spread(),
                            "connectionGeneration", supervisor.snapshot().generation()
                    ),
                    false
            );
            priceSnapshotService.saveSnapshot(null, market.id(), remainingDuration, up, down, capturedAt);
            marketDepthSnapshotService.saveSnapshots(market.id(), remainingDuration, orderBookState, capturedAt);
        }

        private boolean seedStateFromRestOrderBooks(boolean requireCompleteSeed) {
            Map<String, OrderBookDto> booksByTokenId = new LinkedHashMap<>();
            boolean complete = true;
            for (Map.Entry<String, String> entry : tokenMap.outcomeByTokenId().entrySet()) {
                String tokenId = entry.getKey();
                String outcome = entry.getValue();
                try {
                    OrderBookDto book = clobClient.getOrderBook(tokenId).block();
                    if (book == null) {
                        complete = false;
                        log.warn("{}No REST order book returned for marketId={} outcome={} tokenId={}{}",
                                LogColors.MARKET, market.id(), outcome, tokenId, LogColors.RESET);
                        eventLogger.price(
                                "PRICE_SEED_MISSING_BOOK",
                                null,
                                market,
                                null,
                                "REST order book missing",
                                TelemetryData.data("outcome", outcome, "tokenId", tokenId, "strict", requireCompleteSeed),
                                true
                        );
                        continue;
                    }
                    booksByTokenId.put(tokenId, book);
                } catch (Exception e) {
                    complete = false;
                    log.warn("{}Failed to seed REST book for marketId={} outcome={} tokenId={}{}",
                            LogColors.MARKET, market.id(), outcome, tokenId, LogColors.RESET, e);
                    eventLogger.price(
                            "PRICE_SEED_FAILED",
                            null,
                            market,
                            null,
                            "REST seed failed",
                            TelemetryData.data(
                                    "outcome", outcome,
                                    "tokenId", tokenId,
                                    "strict", requireCompleteSeed,
                                    "error", e.getMessage()
                            ),
                            true
                    );
                }
            }

            if (requireCompleteSeed && (!complete || booksByTokenId.size() != tokenMap.outcomeByTokenId().size())) {
                return false;
            }

            if (requireCompleteSeed) {
                latestPriceState.clear();
                orderBookState.clear();
            }
            booksByTokenId.forEach((tokenId, book) -> publishSeed(tokenId, tokenMap.outcomeFor(tokenId), book));
            return complete;
        }

        private void publishSeed(String tokenId, String outcome, OrderBookDto book) {
            Instant updatedAt = Instant.now();
            orderBookState.update(tokenId, outcome, book.bids(), book.asks(), updatedAt);
            latestPriceState.update(tokenId, outcome, book.bestBid().orElse(null), book.bestAsk().orElse(null), updatedAt);
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
                            "spread", book.spread().orElse(null),
                            "connectionGeneration", supervisor.snapshot().generation()
                    ),
                    false
            );
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
            if (message.isBook() && (message.bids() != null || message.asks() != null)) {
                orderBookState.update(tokenId, outcome, message.bids(), message.asks(), Instant.now());
                OutcomeOrderBook book = orderBookState.byTokenId(tokenId).orElse(null);
                if (book != null) {
                    bid = book.bestBid().map(OrderBookLevel::price).orElse(bid);
                    ask = book.bestAsk().map(OrderBookLevel::price).orElse(ask);
                }
            }
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
                orderBookState.applyPriceChange(
                        tokenId,
                        outcome,
                        change.side(),
                        change.price(),
                        change.size(),
                        Instant.now()
                );
                BigDecimal bid = parseDecimal(change.bestBid()).orElse(null);
                BigDecimal ask = parseDecimal(change.bestAsk()).orElse(null);
                latestPriceState.update(tokenId, outcome, bid, ask);
                logPriceUpdate(message.eventType(), tokenId, outcome, bid, ask);
            });
        }

        private void logPriceUpdate(String eventType, String tokenId, String outcome, BigDecimal bid, BigDecimal ask) {
            if (!logLiveUpdates) {
                return;
            }
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
                            "spread", ask != null && bid != null ? ask.subtract(bid) : null,
                            "connectionGeneration", supervisor.snapshot().generation()
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

        private void emitSupervision(String eventType, String description, boolean important) {
            MarketStreamSupervisor.Snapshot snapshot = supervisor.snapshot();
            log.warn("{}{} marketId={} generation={} state={} paused={} reason={} reconnects={} gaps={} stale={} reseeds={}{}",
                    LogColors.MARKET,
                    eventType,
                    market.id(),
                    snapshot.generation(),
                    snapshot.state(),
                    snapshot.strategyPaused(),
                    snapshot.pauseReason(),
                    snapshot.reconnectCount(),
                    snapshot.gapCount(),
                    snapshot.staleCount(),
                    snapshot.reseedCount(),
                    LogColors.RESET);
            eventLogger.market(eventType, null, market, description, supervisionData(snapshot), important);
        }

        private Map<String, Object> supervisionData(MarketStreamSupervisor.Snapshot snapshot) {
            return TelemetryData.data(
                    "connectionGeneration", snapshot.generation(),
                    "state", snapshot.state(),
                    "strategyPaused", snapshot.strategyPaused(),
                    "pauseReason", snapshot.pauseReason(),
                    "lastHeartbeatAt", snapshot.lastHeartbeatAt(),
                    "lastExchangeEventAt", snapshot.lastExchangeEventAt(),
                    "reconnectCount", snapshot.reconnectCount(),
                    "gapCount", snapshot.gapCount(),
                    "staleCount", snapshot.staleCount(),
                    "pauseCount", snapshot.pauseCount(),
                    "reseedCount", snapshot.reseedCount()
            );
        }

        private Optional<Instant> parseEventTimestamp(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            try {
                long raw = Long.parseLong(value);
                return Optional.of(value.length() <= 10 ? Instant.ofEpochSecond(raw) : Instant.ofEpochMilli(raw));
            } catch (NumberFormatException ignored) {
                try {
                    return Optional.of(Instant.parse(value));
                } catch (DateTimeParseException invalidTimestamp) {
                    return Optional.empty();
                }
            }
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

        private final class FeedSocketObserver implements MarketWebSocketObserver {
            @Override
            public void onConnected() {
                if (this != activeObserver || stopped) {
                    return;
                }
                long generation = supervisor.onConnected(Instant.now());
                if (generation > 1) {
                    latestPriceState.clear();
                    orderBookState.clear();
                    emitSupervision("MARKET_WS_RECONNECTED", "websocket reconnected; REST reseed required", true);
                }
            }

            @Override
            public void onHeartbeat(Instant receivedAt) {
                if (this == activeObserver && !stopped) {
                    supervisor.onHeartbeat(receivedAt);
                }
            }

            @Override
            public void onDisconnected(Throwable cause) {
                if (this != activeObserver || stopped) {
                    return;
                }
                supervisor.onDisconnected(Instant.now(), cause);
                latestPriceState.clear();
                orderBookState.clear();
                emitSupervision("MARKET_WS_DISCONNECTED", "websocket disconnected; strategy data paused", true);
            }

            @Override
            public void accept(MarketWsMessageDto message) {
                if (this != activeObserver || stopped || message == null) {
                    return;
                }
                MarketStreamSupervisor.MessageDecision decision = supervisor.onMarketMessage(
                        parseEventTimestamp(message.timestamp()).orElse(null),
                        Instant.now()
                );
                if (decision == MarketStreamSupervisor.MessageDecision.RECONNECT_FOR_GAP) {
                    emitSupervision("MARKET_WS_GAP_DETECTED", "market event timestamp gap detected", true);
                    restartWebSocket("STREAM_GAP");
                    return;
                }
                if (decision == MarketStreamSupervisor.MessageDecision.PAUSED) {
                    return;
                }
                handleMarketMessage(message, market.id());
            }
        }
    }

    private long positiveOrDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private String formatRemaining(Duration remaining) {
        if (remaining == null) {
            return null;
        }

        long seconds = remaining.getSeconds();
        boolean negative = seconds < 0;
        seconds = Math.abs(seconds);

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        String prefix = negative ? "-" : "";

        if (hours > 0) {
            return "%s%dh%02dm%02ds".formatted(prefix, hours, minutes, remainingSeconds);
        }

        return "%s%dm%02ds".formatted(prefix, minutes, remainingSeconds);
    }
}
