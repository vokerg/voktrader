package com.vokerg.voktrader.market;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.outbox.MarketResolvedOutboxPayload;
import com.vokerg.voktrader.outbox.OutboxEventService;
import com.vokerg.voktrader.outbox.OutboxEventType;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketResolutionService {
    private static final String AGGREGATE_TYPE = "MARKET";

    private final MarketRepository marketRepository;
    private final OutboxEventService outboxEventService;
    private final TradingEventLogger eventLogger;

    @Transactional
    public void resolveMarket(
            String marketId,
            String winningAssetId,
            String winningOutcome,
            String source
    ) {
        if (marketId == null || marketId.isBlank() || winningOutcome == null || winningOutcome.isBlank()) {
            return;
        }

        MarketEntity market = marketRepository.findByPolymarketMarketId(marketId)
                .orElseGet(() -> {
                    MarketEntity created = new MarketEntity();
                    created.setPolymarketMarketId(marketId);
                    created.setFirstSeenAt(Instant.now());
                    return created;
                });

        boolean duplicate = MarketResolutionStatus.RESOLVED.equals(market.getResolutionStatus()) || market.isResolved();
        Instant now = Instant.now();
        Instant resolvedAt = market.getResolvedAt() == null ? now : market.getResolvedAt();
        String eventWinningOutcome = market.getWinningOutcome() == null || market.getWinningOutcome().isBlank()
                ? winningOutcome
                : market.getWinningOutcome();
        String eventWinningAssetId = market.getWinningAssetId() == null || market.getWinningAssetId().isBlank()
                ? winningAssetId
                : market.getWinningAssetId();
        String eventSource = market.getResolutionSource() == null || market.getResolutionSource().isBlank()
                ? source
                : market.getResolutionSource();

        if (!duplicate || market.getWinningOutcome() == null || market.getWinningOutcome().isBlank()) {
            market.setResolved(true);
            market.setClosed(true);
            market.setActive(false);
            market.setAcceptingOrders(false);
            market.setTrackingStatus(MarketTrackingStatus.STOPPED);
            market.setResolutionStatus(MarketResolutionStatus.RESOLVED);
            market.setWinningAssetId(eventWinningAssetId);
            market.setWinningOutcome(eventWinningOutcome);
            market.setResolutionSource(eventSource);
            market.setResolvedAt(resolvedAt);
            market.setLastResolutionCheckAt(now);
            market.setLastSeenAt(now);
            marketRepository.save(market);
        }

        outboxEventService.enqueueOnce(
                OutboxEventType.MARKET_RESOLVED,
                idempotencyKey(marketId),
                AGGREGATE_TYPE,
                marketId,
                new MarketResolvedOutboxPayload(
                        marketId,
                        eventWinningAssetId,
                        eventWinningOutcome,
                        eventSource,
                        resolvedAt
                )
        );

        if (duplicate) {
            log.info(
                    "{}Ignoring duplicate market resolution: marketId={} winningOutcome={} source={}{}",
                    LogColors.TRADE,
                    marketId,
                    eventWinningOutcome,
                    source,
                    LogColors.RESET);
            eventLogger.market(
                    "MARKET_RESOLUTION_DUPLICATE",
                    null,
                    null,
                    "duplicate resolution",
                    TelemetryData.data(
                            "marketId", marketId,
                            "winningOutcome", eventWinningOutcome,
                            "source", source
                    ),
                    true
            );
            return;
        }

        log.info(
                "{}MARKET RESOLUTION RECORDED mode={} marketId={} winningOutcome={} winningAssetId={} source={} outboxKey={}{}",
                LogColors.TRADE,
                resolutionMode(source),
                marketId,
                eventWinningOutcome,
                eventWinningAssetId,
                source,
                idempotencyKey(marketId),
                LogColors.RESET);
        eventLogger.market(
                "MARKET_RESOLVED",
                null,
                null,
                "market resolved",
                TelemetryData.data(
                        "marketId", marketId,
                        "winningOutcome", eventWinningOutcome,
                        "winningAssetId", eventWinningAssetId,
                        "source", source,
                        "resolvedAt", resolvedAt
                ),
                true
        );
    }

    public static String idempotencyKey(String marketId) {
        return "MARKET_RESOLVED:" + marketId;
    }

    private String resolutionMode(String source) {
        if ("websocket".equalsIgnoreCase(source)) {
            return "NORMAL";
        }
        if ("gamma_poll".equalsIgnoreCase(source)) {
            return "EMERGENCY";
        }
        return "UNKNOWN";
    }
}
