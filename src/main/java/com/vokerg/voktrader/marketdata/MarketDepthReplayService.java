package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotEntity;
import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotLevelEntity;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotLevelRepository;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MarketDepthReplayService {
    private static final int SCORE_SCALE = 8;

    private final MarketDepthSnapshotRepository snapshotRepository;
    private final MarketDepthSnapshotLevelRepository levelRepository;
    private final MarketDepthSnapshotProperties properties;
    private final HistoricalTickCoverageService historicalTickCoverageService;

    @Autowired
    public MarketDepthReplayService(
            MarketDepthSnapshotRepository snapshotRepository,
            MarketDepthSnapshotLevelRepository levelRepository,
            MarketDepthSnapshotProperties properties,
            HistoricalTickCoverageService historicalTickCoverageService
    ) {
        this.snapshotRepository = snapshotRepository;
        this.levelRepository = levelRepository;
        this.properties = properties;
        this.historicalTickCoverageService = historicalTickCoverageService;
    }

    MarketDepthReplayService(
            MarketDepthSnapshotRepository snapshotRepository,
            MarketDepthSnapshotLevelRepository levelRepository,
            MarketDepthSnapshotProperties properties
    ) {
        this(snapshotRepository, levelRepository, properties, null);
    }

    @Transactional(readOnly = true)
    public ReplayDepthSnapshot loadExactSnapshot(Long marketId, Instant capturedAt) {
        if (marketId == null) {
            throw new IllegalArgumentException("replay marketId is required");
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("replay capturedAt is required");
        }

        List<MarketDepthSnapshotEntity> summaries = snapshotRepository.findByMarketIdAndCapturedAt(marketId, capturedAt);
        List<MarketDepthSnapshotLevelEntity> levels = levelRepository
                .findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(marketId, capturedAt);
        if (summaries.isEmpty()) {
            throw corrupt(marketId, capturedAt, "summary rows are missing");
        }
        if (levels.isEmpty()) {
            throw corrupt(marketId, capturedAt, "depth level rows are missing");
        }

        Map<String, MarketDepthSnapshotEntity> summaryByToken = indexSummaries(marketId, capturedAt, summaries);
        Map<String, List<MarketDepthSnapshotLevelEntity>> levelsByToken = indexLevels(marketId, capturedAt, levels);
        if (!summaryByToken.keySet().equals(levelsByToken.keySet())) {
            Set<String> missingLevels = new HashSet<>(summaryByToken.keySet());
            missingLevels.removeAll(levelsByToken.keySet());
            Set<String> missingSummaries = new HashSet<>(levelsByToken.keySet());
            missingSummaries.removeAll(summaryByToken.keySet());
            throw corrupt(
                    marketId,
                    capturedAt,
                    "summary/level token sets differ; missingLevels=" + missingLevels + " missingSummaries=" + missingSummaries
            );
        }

        Map<String, OutcomeOrderBook> books = new LinkedHashMap<>();
        Map<String, ReplayDepthCoverage> coverage = new LinkedHashMap<>();
        for (Map.Entry<String, MarketDepthSnapshotEntity> entry : summaryByToken.entrySet()) {
            String tokenId = entry.getKey();
            MarketDepthSnapshotEntity summary = entry.getValue();
            OutcomeOrderBook book = buildBook(marketId, capturedAt, summary, levelsByToken.get(tokenId));
            assertSummaryMatches(marketId, capturedAt, summary, book);
            if (historicalTickCoverageService != null) {
                historicalTickCoverageService.assertReplayReady(
                        HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                        marketId,
                        tokenId,
                        capturedAt
                );
            }
            books.put(tokenId, book);
            coverage.put(tokenId, new ReplayDepthCoverage(book.bids().size(), book.asks().size()));
        }

        return new ReplayDepthSnapshot(marketId, capturedAt, Map.copyOf(books), Map.copyOf(coverage));
    }

    private Map<String, MarketDepthSnapshotEntity> indexSummaries(
            Long marketId,
            Instant capturedAt,
            List<MarketDepthSnapshotEntity> summaries
    ) {
        Map<String, MarketDepthSnapshotEntity> indexed = new LinkedHashMap<>();
        for (MarketDepthSnapshotEntity summary : summaries) {
            if (summary == null) {
                throw corrupt(marketId, capturedAt, "null summary row");
            }
            String tokenId = requireText(summary.getTokenId(), "summary tokenId", marketId, capturedAt);
            requireText(summary.getOutcome(), "summary outcome", marketId, capturedAt);
            assertSame(marketId, summary.getMarketId(), "summary marketId", marketId, capturedAt, tokenId);
            assertSame(capturedAt, summary.getCapturedAt(), "summary capturedAt", marketId, capturedAt, tokenId);
            if (indexed.putIfAbsent(tokenId, summary) != null) {
                throw corrupt(marketId, capturedAt, "duplicate summary for tokenId=" + tokenId);
            }
        }
        return indexed;
    }

    private Map<String, List<MarketDepthSnapshotLevelEntity>> indexLevels(
            Long marketId,
            Instant capturedAt,
            List<MarketDepthSnapshotLevelEntity> levels
    ) {
        Map<String, List<MarketDepthSnapshotLevelEntity>> indexed = new LinkedHashMap<>();
        for (MarketDepthSnapshotLevelEntity level : levels) {
            if (level == null) {
                throw corrupt(marketId, capturedAt, "null level row");
            }
            String tokenId = requireText(level.getTokenId(), "level tokenId", marketId, capturedAt);
            requireText(level.getOutcome(), "level outcome", marketId, capturedAt);
            assertSame(marketId, level.getMarketId(), "level marketId", marketId, capturedAt, tokenId);
            assertSame(capturedAt, level.getCapturedAt(), "level capturedAt", marketId, capturedAt, tokenId);
            if (level.getSide() == null || level.getLevelIndex() == null || level.getLevelIndex() <= 0) {
                throw corrupt(marketId, capturedAt, "invalid side/index for tokenId=" + tokenId);
            }
            OrderBookLevel value = new OrderBookLevel(level.getPrice(), level.getSize());
            if (!value.usable()) {
                throw corrupt(marketId, capturedAt, "invalid price/size for tokenId=" + tokenId);
            }
            indexed.computeIfAbsent(tokenId, ignored -> new ArrayList<>()).add(level);
        }
        return indexed;
    }

    private OutcomeOrderBook buildBook(
            Long marketId,
            Instant capturedAt,
            MarketDepthSnapshotEntity summary,
            List<MarketDepthSnapshotLevelEntity> rows
    ) {
        Map<OrderBookSide, List<MarketDepthSnapshotLevelEntity>> bySide = new EnumMap<>(OrderBookSide.class);
        for (MarketDepthSnapshotLevelEntity row : rows) {
            if (!summary.getOutcome().equals(row.getOutcome())) {
                throw corrupt(marketId, capturedAt, "outcome mismatch for tokenId=" + summary.getTokenId());
            }
            if (!same(summary.getBookUpdatedAt(), row.getBookUpdatedAt())) {
                throw corrupt(marketId, capturedAt, "bookUpdatedAt mismatch for tokenId=" + summary.getTokenId());
            }
            bySide.computeIfAbsent(row.getSide(), ignored -> new ArrayList<>()).add(row);
        }

        List<OrderBookLevel> bids = exactSide(
                marketId,
                capturedAt,
                summary.getTokenId(),
                OrderBookSide.BUY,
                bySide.get(OrderBookSide.BUY),
                Comparator.reverseOrder()
        );
        List<OrderBookLevel> asks = exactSide(
                marketId,
                capturedAt,
                summary.getTokenId(),
                OrderBookSide.SELL,
                bySide.get(OrderBookSide.SELL),
                Comparator.naturalOrder()
        );
        return new OutcomeOrderBook(summary.getTokenId(), summary.getOutcome(), bids, asks, summary.getBookUpdatedAt());
    }

    private List<OrderBookLevel> exactSide(
            Long marketId,
            Instant capturedAt,
            String tokenId,
            OrderBookSide side,
            List<MarketDepthSnapshotLevelEntity> rows,
            Comparator<BigDecimal> priceOrder
    ) {
        if (rows == null || rows.isEmpty()) {
            throw corrupt(marketId, capturedAt, "missing " + side + " levels for tokenId=" + tokenId);
        }
        List<MarketDepthSnapshotLevelEntity> ordered = rows.stream()
                .sorted(Comparator.comparing(MarketDepthSnapshotLevelEntity::getLevelIndex))
                .toList();
        List<OrderBookLevel> result = new ArrayList<>(ordered.size());
        BigDecimal previousPrice = null;
        for (int i = 0; i < ordered.size(); i++) {
            MarketDepthSnapshotLevelEntity row = ordered.get(i);
            int expectedIndex = i + 1;
            if (row.getLevelIndex() != expectedIndex) {
                throw corrupt(
                        marketId,
                        capturedAt,
                        "non-contiguous " + side + " levelIndex for tokenId=" + tokenId
                                + "; expected=" + expectedIndex + " actual=" + row.getLevelIndex()
                );
            }
            if (previousPrice != null && priceOrder.compare(previousPrice, row.getPrice()) >= 0) {
                throw corrupt(marketId, capturedAt, "non-strict " + side + " price ordering for tokenId=" + tokenId);
            }
            previousPrice = row.getPrice();
            result.add(new OrderBookLevel(row.getPrice(), row.getSize()));
        }
        return List.copyOf(result);
    }

    private void assertSummaryMatches(
            Long marketId,
            Instant capturedAt,
            MarketDepthSnapshotEntity summary,
            OutcomeOrderBook book
    ) {
        assertDecimal(summary.getBestBid(), book.bestBid().orElseThrow().price(), "bestBid", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getBestAsk(), book.bestAsk().orElseThrow().price(), "bestAsk", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getSpread(), book.spread().orElseThrow(), "spread", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getBidDepth(), book.bidDepth(), "bidDepth", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getAskDepth(), book.askDepth(), "askDepth", marketId, capturedAt, book.tokenId());

        BigDecimal nearBidDepth = book.bidDepthWithin(properties.nearTopRange());
        BigDecimal nearAskDepth = book.askDepthWithin(properties.nearTopRange());
        assertDecimal(summary.getNearBidDepth(), nearBidDepth, "nearBidDepth", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getNearAskDepth(), nearAskDepth, "nearAskDepth", marketId, capturedAt, book.tokenId());
        assertDecimal(
                summary.getDepthImbalance(),
                imbalance(book.bidDepth(), book.askDepth()),
                "depthImbalance",
                marketId,
                capturedAt,
                book.tokenId()
        );
        assertDecimal(
                summary.getNearDepthImbalance(),
                imbalance(nearBidDepth, nearAskDepth),
                "nearDepthImbalance",
                marketId,
                capturedAt,
                book.tokenId()
        );

        FillEstimate estimate = book.estimateBuyUsd(summary.getEstimateBuyUsd());
        assertDecimal(summary.getEstimateBuyFilledShares(), estimate.filledShares(), "estimateBuyFilledShares", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getEstimateBuyAveragePrice(), estimate.averagePrice(), "estimateBuyAveragePrice", marketId, capturedAt, book.tokenId());
        assertDecimal(summary.getEstimateBuyWorstPrice(), estimate.worstPrice(), "estimateBuyWorstPrice", marketId, capturedAt, book.tokenId());
        if (!same(summary.getEstimateBuyComplete(), estimate.complete())) {
            throw mismatch("estimateBuyComplete", summary.getEstimateBuyComplete(), estimate.complete(), marketId, capturedAt, book.tokenId());
        }
        if (!same(summary.getEstimateBuyLevelsConsumed(), estimate.levelsConsumed())) {
            throw mismatch(
                    "estimateBuyLevelsConsumed",
                    summary.getEstimateBuyLevelsConsumed(),
                    estimate.levelsConsumed(),
                    marketId,
                    capturedAt,
                    book.tokenId()
            );
        }
        Long expectedAgeMs = summary.getBookUpdatedAt() == null
                ? null
                : Duration.between(summary.getBookUpdatedAt(), capturedAt).toMillis();
        if (!same(summary.getBookAgeMs(), expectedAgeMs)) {
            throw mismatch("bookAgeMs", summary.getBookAgeMs(), expectedAgeMs, marketId, capturedAt, book.tokenId());
        }
    }

    private BigDecimal imbalance(BigDecimal bidDepth, BigDecimal askDepth) {
        BigDecimal bid = bidDepth == null ? BigDecimal.ZERO : bidDepth;
        BigDecimal ask = askDepth == null ? BigDecimal.ZERO : askDepth;
        BigDecimal total = bid.add(ask);
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return bid.subtract(ask).divide(total, SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private String requireText(String value, String field, Long marketId, Instant capturedAt) {
        if (value == null || value.isBlank()) {
            throw corrupt(marketId, capturedAt, field + " is missing");
        }
        return value;
    }

    private void assertDecimal(
            BigDecimal stored,
            BigDecimal derived,
            String field,
            Long marketId,
            Instant capturedAt,
            String tokenId
    ) {
        if (stored == null || derived == null || stored.compareTo(derived) != 0) {
            throw mismatch(field, stored, derived, marketId, capturedAt, tokenId);
        }
    }

    private void assertSame(
            Object expected,
            Object actual,
            String field,
            Long marketId,
            Instant capturedAt,
            String tokenId
    ) {
        if (!same(expected, actual)) {
            throw mismatch(field, expected, actual, marketId, capturedAt, tokenId);
        }
    }

    private boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private ReplayDepthCorruptionException mismatch(
            String field,
            Object stored,
            Object derived,
            Long marketId,
            Instant capturedAt,
            String tokenId
    ) {
        return corrupt(
                marketId,
                capturedAt,
                "summary mismatch for tokenId=" + tokenId + " field=" + field
                        + " stored=" + stored + " derived=" + derived
        );
    }

    private ReplayDepthCorruptionException corrupt(Long marketId, Instant capturedAt, String reason) {
        return new ReplayDepthCorruptionException(
                "recorded depth replay is corrupt for marketId=" + marketId + " capturedAt=" + capturedAt + ": " + reason
        );
    }

    public record ReplayDepthSnapshot(
            Long marketId,
            Instant capturedAt,
            Map<String, OutcomeOrderBook> booksByTokenId,
            Map<String, ReplayDepthCoverage> coverageByTokenId
    ) {
    }

    public record ReplayDepthCoverage(int bidLevels, int askLevels) {
    }

    public static class ReplayDepthCorruptionException extends IllegalStateException {
        public ReplayDepthCorruptionException(String message) {
            super(message);
        }
    }
}
