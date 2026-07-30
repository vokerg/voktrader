package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotEntity;
import com.vokerg.voktrader.marketdata.model.MarketDepthSnapshotLevelEntity;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotLevelRepository;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDepthReplayServiceTest {
    private static final Long MARKET_ID = 42L;
    private static final Instant UPDATED_AT = Instant.parse("2026-07-20T10:00:00Z");
    private static final Instant CAPTURED_AT = UPDATED_AT.plusMillis(250);
    private static final BigDecimal NEAR_RANGE = new BigDecimal("0.02");
    private static final BigDecimal ESTIMATE_USD = new BigDecimal("1.00");

    private final MarketDepthSnapshotRepository snapshotRepository = mock(MarketDepthSnapshotRepository.class);
    private final MarketDepthSnapshotLevelRepository levelRepository = mock(MarketDepthSnapshotLevelRepository.class);
    private final HistoricalTickCoverageService coverageService = mock(HistoricalTickCoverageService.class);
    private final MarketDepthReplayService service = new MarketDepthReplayService(
            snapshotRepository,
            levelRepository,
            new MarketDepthSnapshotProperties(NEAR_RANGE, 10),
            coverageService
    );

    @Test
    void goldenTickReplaysExactPersistedLevelsAndCapturedFeatures() {
        OutcomeOrderBook captured = goldenBook();
        MarketDepthSnapshotEntity summary = summary(captured);
        List<MarketDepthSnapshotLevelEntity> levels = levels(captured);
        when(snapshotRepository.findByMarketIdAndCapturedAt(MARKET_ID, CAPTURED_AT)).thenReturn(List.of(summary));
        when(levelRepository.findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(MARKET_ID, CAPTURED_AT))
                .thenReturn(levels);

        MarketDepthReplayService.ReplayDepthSnapshot replay = service.loadExactSnapshot(MARKET_ID, CAPTURED_AT);

        OutcomeOrderBook replayed = replay.booksByTokenId().get("token-up");
        assertThat(replayed).isEqualTo(captured);
        assertThat(replay.coverageByTokenId().get("token-up"))
                .isEqualTo(new MarketDepthReplayService.ReplayDepthCoverage(3, 3));
        assertThat(replayed.bestBid().orElseThrow().price()).isEqualByComparingTo(summary.getBestBid());
        assertThat(replayed.bestAsk().orElseThrow().price()).isEqualByComparingTo(summary.getBestAsk());
        assertThat(replayed.bidDepth()).isEqualByComparingTo(summary.getBidDepth());
        assertThat(replayed.askDepth()).isEqualByComparingTo(summary.getAskDepth());
        assertThat(replayed.bidDepthWithin(NEAR_RANGE)).isEqualByComparingTo(summary.getNearBidDepth());
        assertThat(replayed.askDepthWithin(NEAR_RANGE)).isEqualByComparingTo(summary.getNearAskDepth());
        FillEstimate estimate = replayed.estimateBuyUsd(ESTIMATE_USD);
        assertThat(estimate.filledShares()).isEqualByComparingTo(summary.getEstimateBuyFilledShares());
        assertThat(estimate.averagePrice()).isEqualByComparingTo(summary.getEstimateBuyAveragePrice());
        assertThat(estimate.worstPrice()).isEqualByComparingTo(summary.getEstimateBuyWorstPrice());
        assertThat(estimate.complete()).isEqualTo(summary.getEstimateBuyComplete());
        assertThat(estimate.levelsConsumed()).isEqualTo(summary.getEstimateBuyLevelsConsumed());
        verify(coverageService).assertReplayReady(
                HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                MARKET_ID,
                "token-up",
                CAPTURED_AT
        );
    }

    @Test
    void discontinuousLevelIndexRejectsTickInsteadOfSynthesizingMissingDepth() {
        OutcomeOrderBook captured = goldenBook();
        List<MarketDepthSnapshotLevelEntity> levels = new ArrayList<>(levels(captured));
        levels.set(1, MarketDepthSnapshotLevelEntity.snapshot(
                null,
                MARKET_ID,
                90L,
                captured,
                OrderBookSide.BUY,
                4,
                captured.bids().get(1),
                CAPTURED_AT
        ));
        when(snapshotRepository.findByMarketIdAndCapturedAt(MARKET_ID, CAPTURED_AT)).thenReturn(List.of(summary(captured)));
        when(levelRepository.findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(MARKET_ID, CAPTURED_AT))
                .thenReturn(levels);

        assertThatThrownBy(() -> service.loadExactSnapshot(MARKET_ID, CAPTURED_AT))
                .isInstanceOf(MarketDepthReplayService.ReplayDepthCorruptionException.class)
                .hasMessageContaining("non-contiguous BUY levelIndex")
                .hasMessageContaining("expected=2 actual=4");
    }

    @Test
    void summaryDepthMismatchRejectsCorruptOrTruncatedTick() {
        OutcomeOrderBook levelsBook = goldenBook();
        OutcomeOrderBook summaryBook = new OutcomeOrderBook(
                "token-up",
                "Up",
                List.of(
                        new OrderBookLevel(new BigDecimal("0.49"), new BigDecimal("2")),
                        new OrderBookLevel(new BigDecimal("0.48"), new BigDecimal("3")),
                        new OrderBookLevel(new BigDecimal("0.47"), new BigDecimal("104"))
                ),
                levelsBook.asks(),
                UPDATED_AT
        );
        when(snapshotRepository.findByMarketIdAndCapturedAt(MARKET_ID, CAPTURED_AT)).thenReturn(List.of(summary(summaryBook)));
        when(levelRepository.findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(MARKET_ID, CAPTURED_AT))
                .thenReturn(levels(levelsBook));

        assertThatThrownBy(() -> service.loadExactSnapshot(MARKET_ID, CAPTURED_AT))
                .isInstanceOf(MarketDepthReplayService.ReplayDepthCorruptionException.class)
                .hasMessageContaining("field=bidDepth")
                .hasMessageContaining("stored=109")
                .hasMessageContaining("derived=9");
    }

    @Test
    void missingSideRejectsIncompleteTick() {
        OutcomeOrderBook captured = goldenBook();
        List<MarketDepthSnapshotLevelEntity> onlyBids = levels(captured).stream()
                .filter(level -> level.getSide() == OrderBookSide.BUY)
                .toList();
        when(snapshotRepository.findByMarketIdAndCapturedAt(MARKET_ID, CAPTURED_AT)).thenReturn(List.of(summary(captured)));
        when(levelRepository.findByMarketIdAndCapturedAtOrderByTokenIdAscSideAscLevelIndexAsc(MARKET_ID, CAPTURED_AT))
                .thenReturn(onlyBids);

        assertThatThrownBy(() -> service.loadExactSnapshot(MARKET_ID, CAPTURED_AT))
                .isInstanceOf(MarketDepthReplayService.ReplayDepthCorruptionException.class)
                .hasMessageContaining("missing SELL levels");
    }

    private OutcomeOrderBook goldenBook() {
        return new OutcomeOrderBook(
                "token-up",
                "Up",
                List.of(
                        new OrderBookLevel(new BigDecimal("0.49"), new BigDecimal("2")),
                        new OrderBookLevel(new BigDecimal("0.48"), new BigDecimal("3")),
                        new OrderBookLevel(new BigDecimal("0.47"), new BigDecimal("4"))
                ),
                List.of(
                        new OrderBookLevel(new BigDecimal("0.51"), new BigDecimal("1")),
                        new OrderBookLevel(new BigDecimal("0.52"), new BigDecimal("3")),
                        new OrderBookLevel(new BigDecimal("0.53"), new BigDecimal("5"))
                ),
                UPDATED_AT
        );
    }

    private MarketDepthSnapshotEntity summary(OutcomeOrderBook book) {
        return MarketDepthSnapshotEntity.snapshot(
                null,
                MARKET_ID,
                90L,
                book,
                NEAR_RANGE,
                ESTIMATE_USD,
                1500,
                CAPTURED_AT
        );
    }

    private List<MarketDepthSnapshotLevelEntity> levels(OutcomeOrderBook book) {
        List<MarketDepthSnapshotLevelEntity> result = new ArrayList<>();
        for (int i = 0; i < book.bids().size(); i++) {
            result.add(MarketDepthSnapshotLevelEntity.snapshot(
                    null,
                    MARKET_ID,
                    90L,
                    book,
                    OrderBookSide.BUY,
                    i + 1,
                    book.bids().get(i),
                    CAPTURED_AT
            ));
        }
        for (int i = 0; i < book.asks().size(); i++) {
            result.add(MarketDepthSnapshotLevelEntity.snapshot(
                    null,
                    MARKET_ID,
                    90L,
                    book,
                    OrderBookSide.SELL,
                    i + 1,
                    book.asks().get(i),
                    CAPTURED_AT
            ));
        }
        return result;
    }
}
