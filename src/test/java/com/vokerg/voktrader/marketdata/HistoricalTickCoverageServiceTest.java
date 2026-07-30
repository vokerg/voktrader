package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.HistoricalTickCoverageEntity;
import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.HistoricalTickCoverageRepository;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotLevelRepository;
import com.vokerg.voktrader.marketdata.persistence.PriceSnapshotRepository;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalTickCoverageServiceTest {
    private final MarketDepthSnapshotLevelRepository depthRepository = mock(MarketDepthSnapshotLevelRepository.class);
    private final PriceSnapshotRepository priceRepository = mock(PriceSnapshotRepository.class);
    private final TickSizeMetadataRepository tickRepository = mock(TickSizeMetadataRepository.class);
    private final HistoricalTickCoverageRepository coverageRepository = mock(HistoricalTickCoverageRepository.class);
    private final HistoricalTickCoverageService service = new HistoricalTickCoverageService(
            depthRepository,
            priceRepository,
            tickRepository,
            coverageRepository
    );

    @Test
    void missingTickEvidenceCreatesExplicitFullIntervalBlocker() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Instant end = Instant.parse("2026-07-20T10:05:00Z");
        when(depthRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(depthInterval(7L, "token-up", start, end)));
        when(tickRepository.findFirstByTokenIdOrderByEffectiveAtAscIdAsc("token-up")).thenReturn(Optional.empty());

        HistoricalTickCoverageService.InventoryResult result = service.inventoryDepthSnapshotCoverage();

        HistoricalTickCoverageEntity blocker = savedCoverage().getFirst();
        assertThat(result.blockersCreated()).isEqualTo(1);
        assertThat(blocker.getDatasetType()).isEqualTo(HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT);
        assertThat(blocker.getCoverageStatus()).isEqualTo(HistoricalTickCoverageStatus.BLOCKED);
        assertThat(blocker.getEvidenceSource()).isEqualTo(HistoricalTickEvidenceSource.UNRESOLVED);
        assertThat(blocker.getTickSize()).isNull();
        assertThat(blocker.getIntervalStartAt()).isEqualTo(start);
        assertThat(blocker.getIntervalEndAt()).isEqualTo(end);
        assertThat(blocker.getBlockerReason()).contains("must not be used to infer");
    }

    @Test
    void firstAuthoritativeObservationSplitsBlockerPrefixAndResolvedSuffix() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Instant firstEvidence = Instant.parse("2026-07-20T10:03:00Z");
        Instant end = Instant.parse("2026-07-20T10:05:00Z");
        when(depthRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(depthInterval(7L, "token-up", start, end)));
        when(tickRepository.findFirstByTokenIdOrderByEffectiveAtAscIdAsc("token-up")).thenReturn(Optional.of(metadata(
                "token-up",
                firstEvidence
        )));

        HistoricalTickCoverageService.InventoryResult result = service.inventoryDepthSnapshotCoverage();

        List<HistoricalTickCoverageEntity> saved = savedCoverage();
        HistoricalTickCoverageEntity blocker = saved.stream()
                .filter(item -> item.getCoverageStatus() == HistoricalTickCoverageStatus.BLOCKED)
                .findFirst()
                .orElseThrow();
        HistoricalTickCoverageEntity resolved = saved.stream()
                .filter(item -> item.getCoverageStatus() == HistoricalTickCoverageStatus.RESOLVED)
                .findFirst()
                .orElseThrow();
        assertThat(result.blockersCreated()).isEqualTo(1);
        assertThat(result.resolvedIntervalsCreated()).isEqualTo(1);
        assertThat(blocker.getIntervalStartAt()).isEqualTo(start);
        assertThat(blocker.getIntervalEndAt()).isEqualTo(firstEvidence);
        assertThat(resolved.getIntervalStartAt()).isEqualTo(firstEvidence);
        assertThat(resolved.getIntervalEndAt()).isEqualTo(end);
        assertThat(resolved.getEvidenceSource()).isEqualTo(HistoricalTickEvidenceSource.TICK_METADATA_TIMELINE);
        assertThat(resolved.getSourceReference()).contains("token-up@");
    }

    @Test
    void timelineEvidenceAtIntervalStartPersistsResolvedCoverage() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Instant end = Instant.parse("2026-07-20T10:05:00Z");
        when(depthRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(depthInterval(7L, "token-up", start, end)));
        when(tickRepository.findFirstByTokenIdOrderByEffectiveAtAscIdAsc("token-up")).thenReturn(Optional.of(metadata(
                "token-up",
                start
        )));

        HistoricalTickCoverageService.InventoryResult result = service.inventoryDepthSnapshotCoverage();

        HistoricalTickCoverageEntity resolved = savedCoverage().getFirst();
        assertThat(result.intervalsCoveredByTickTimeline()).isEqualTo(1);
        assertThat(result.blockersCreated()).isZero();
        assertThat(result.resolvedIntervalsCreated()).isEqualTo(1);
        assertThat(resolved.getCoverageStatus()).isEqualTo(HistoricalTickCoverageStatus.RESOLVED);
        assertThat(resolved.getTickSize()).isEqualByComparingTo("0.001");
    }

    @Test
    void priceSnapshotsWithoutTokenMappingCreateMarketLevelBlocker() {
        Instant start = Instant.parse("2026-07-18T10:00:00Z");
        Instant end = Instant.parse("2026-07-18T10:30:00Z");
        when(priceRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(priceInterval(null, 42L, start, end)));

        HistoricalTickCoverageService.PriceSnapshotInventoryResult result = service.inventoryPriceSnapshotCoverage();

        HistoricalTickCoverageEntity blocker = savedCoverage().getFirst();
        assertThat(result.blockersCreated()).isEqualTo(1);
        assertThat(blocker.getDatasetType()).isEqualTo(HistoricalTickDatasetType.PRICE_SNAPSHOT);
        assertThat(blocker.getMarketId()).isEqualTo(42L);
        assertThat(blocker.getTokenId()).isNull();
        assertThat(blocker.getBlockerReason()).contains("market-to-token");
    }

    @Test
    void documentedRestEvidenceCreatesTimelineAndResolvedCoverage() {
        Instant start = Instant.parse("2026-07-18T10:00:00Z");
        Instant end = Instant.parse("2026-07-18T10:30:00Z");
        when(tickRepository.findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
                "token-up",
                start
        )).thenReturn(Optional.empty());

        HistoricalTickCoverageService.ImportResult result = service.importVerifiedEvidence(
                new HistoricalTickCoverageService.VerifiedTickEvidence(
                        HistoricalTickDatasetType.PRICE_SNAPSHOT,
                        42L,
                        "protocol-market-42",
                        "token-up",
                        start,
                        end,
                        new BigDecimal("0.001"),
                        HistoricalTickEvidenceSource.DOCUMENTED_REST_BOOK,
                        "archive/rest-book-42-up.json#tick_size",
                        end
                )
        );

        ArgumentCaptor<TickSizeMetadataEntity> metadataCaptor = ArgumentCaptor.forClass(TickSizeMetadataEntity.class);
        verify(tickRepository).save(metadataCaptor.capture());
        ArgumentCaptor<HistoricalTickCoverageEntity> coverageCaptor = ArgumentCaptor.forClass(HistoricalTickCoverageEntity.class);
        verify(coverageRepository).save(coverageCaptor.capture());
        assertThat(result.metadataCreated()).isTrue();
        assertThat(result.coverageCreated()).isTrue();
        assertThat(metadataCaptor.getValue().getTickSize()).isEqualByComparingTo("0.001");
        assertThat(metadataCaptor.getValue().getSource()).isEqualTo(TickSizeSource.REST_BOOK);
        assertThat(coverageCaptor.getValue().getCoverageStatus()).isEqualTo(HistoricalTickCoverageStatus.RESOLVED);
        assertThat(coverageCaptor.getValue().getSourceReference()).contains("rest-book-42-up");
    }

    @Test
    void activeBlockerRejectsTokenReplayBeforeMetadataLookup() {
        Instant at = Instant.parse("2026-07-18T10:10:00Z");
        HistoricalTickCoverageEntity blocker = HistoricalTickCoverageEntity.blocked(
                HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                42L,
                "token-up",
                at.minusSeconds(60),
                at.plusSeconds(60),
                "authoritative tick evidence is absent",
                at.minusSeconds(1)
        );
        when(coverageRepository
                .findByTokenIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                        "token-up",
                        at,
                        at
                )).thenReturn(List.of(blocker));

        assertThatThrownBy(() -> service.assertTokenReplayReady("token-up", at))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("historical replay is blocked")
                .hasMessageContaining("authoritative tick evidence is absent");
        verify(tickRepository, never())
                .findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(anyString(), any());
    }

    @Test
    void datasetReplayFailsWhenCoverageWasNeverInventoried() {
        Instant at = Instant.parse("2026-07-18T10:10:00Z");
        when(coverageRepository
                .findByDatasetTypeAndMarketIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                        HistoricalTickDatasetType.PRICE_SNAPSHOT,
                        42L,
                        at,
                        at
                )).thenReturn(List.of());

        assertThatThrownBy(() -> service.assertReplayReady(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                "token-up",
                at
        )).hasMessageContaining("historical tick coverage is missing");
    }

    @Test
    void laterTokenResolutionSupersedesEarlierMarketLevelBlocker() {
        Instant at = Instant.parse("2026-07-18T10:10:00Z");
        HistoricalTickCoverageEntity blocker = HistoricalTickCoverageEntity.blocked(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                null,
                at.minusSeconds(60),
                at.plusSeconds(60),
                "token mapping is missing",
                at.minusSeconds(10)
        );
        HistoricalTickCoverageEntity resolved = HistoricalTickCoverageEntity.resolved(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                "token-up",
                at.minusSeconds(60),
                at.plusSeconds(60),
                new BigDecimal("0.001"),
                HistoricalTickEvidenceSource.DOCUMENTED_WEBSOCKET_EVENT,
                "archive/ws-event-42-up.json#new_tick_size",
                at.minusSeconds(1)
        );
        when(coverageRepository
                .findByDatasetTypeAndMarketIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                        HistoricalTickDatasetType.PRICE_SNAPSHOT,
                        42L,
                        at,
                        at
                )).thenReturn(List.of(resolved, blocker));
        when(tickRepository.findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc("token-up", at))
                .thenReturn(Optional.of(metadata("token-up", at.minusSeconds(60))));

        assertThatCode(() -> service.assertReplayReady(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                "token-up",
                at
        )).doesNotThrowAnyException();
    }

    private List<HistoricalTickCoverageEntity> savedCoverage() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<HistoricalTickCoverageEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(coverageRepository, atLeastOnce()).saveAll(captor.capture());
        List<HistoricalTickCoverageEntity> saved = new ArrayList<>();
        for (Iterable<HistoricalTickCoverageEntity> batch : captor.getAllValues()) {
            StreamSupport.stream(batch.spliterator(), false).forEach(saved::add);
        }
        return saved;
    }

    private TickSizeMetadataEntity metadata(String tokenId, Instant effectiveAt) {
        return TickSizeMetadataEntity.observed(
                tokenId,
                "market-7",
                new BigDecimal("0.001"),
                effectiveAt,
                effectiveAt,
                TickSizeSource.REST_BOOK
        );
    }

    private MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection depthInterval(
            Long marketId,
            String tokenId,
            Instant start,
            Instant end
    ) {
        return new MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection() {
            @Override
            public Long getMarketId() { return marketId; }
            @Override
            public String getTokenId() { return tokenId; }
            @Override
            public Instant getIntervalStartAt() { return start; }
            @Override
            public Instant getIntervalEndAt() { return end; }
        };
    }

    private PriceSnapshotRepository.HistoricalTickIntervalProjection priceInterval(
            Long marketId,
            Long marketEntityId,
            Instant start,
            Instant end
    ) {
        return new PriceSnapshotRepository.HistoricalTickIntervalProjection() {
            @Override
            public Long getMarketId() { return marketId; }
            @Override
            public Long getMarketEntityId() { return marketEntityId; }
            @Override
            public Instant getIntervalStartAt() { return start; }
            @Override
            public Instant getIntervalEndAt() { return end; }
        };
    }
}
