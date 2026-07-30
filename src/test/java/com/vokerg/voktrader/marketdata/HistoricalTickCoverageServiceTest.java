package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.HistoricalTickCoverageEntity;
import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.HistoricalTickCoverageRepository;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotLevelRepository;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalTickCoverageServiceTest {
    private final MarketDepthSnapshotLevelRepository depthRepository = mock(MarketDepthSnapshotLevelRepository.class);
    private final TickSizeMetadataRepository tickRepository = mock(TickSizeMetadataRepository.class);
    private final HistoricalTickCoverageRepository coverageRepository = mock(HistoricalTickCoverageRepository.class);
    private final HistoricalTickCoverageService service = new HistoricalTickCoverageService(
            depthRepository,
            tickRepository,
            coverageRepository
    );

    @Test
    void missingTickEvidenceCreatesExplicitFullIntervalBlocker() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Instant end = Instant.parse("2026-07-20T10:05:00Z");
        when(depthRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(interval(7L, "token-up", start, end)));
        when(tickRepository.findFirstByTokenIdOrderByEffectiveAtAscIdAsc("token-up")).thenReturn(Optional.empty());

        HistoricalTickCoverageService.InventoryResult result = service.inventoryDepthSnapshotCoverage();

        HistoricalTickCoverageEntity blocker = savedCoverage();
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
    void firstAuthoritativeObservationCutsOffOnlyTheUncoveredPrefix() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Instant firstEvidence = Instant.parse("2026-07-20T10:03:00Z");
        Instant end = Instant.parse("2026-07-20T10:05:00Z");
        when(depthRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(interval(7L, "token-up", start, end)));
        when(tickRepository.findFirstByTokenIdOrderByEffectiveAtAscIdAsc("token-up")).thenReturn(Optional.of(metadata(
                "token-up",
                firstEvidence
        )));

        service.inventoryDepthSnapshotCoverage();

        HistoricalTickCoverageEntity blocker = savedCoverage();
        assertThat(blocker.getIntervalStartAt()).isEqualTo(start);
        assertThat(blocker.getIntervalEndAt()).isEqualTo(firstEvidence);
    }

    @Test
    void timelineEvidenceAtIntervalStartRequiresNoBackfillOrBlocker() {
        Instant start = Instant.parse("2026-07-20T10:00:00Z");
        Instant end = Instant.parse("2026-07-20T10:05:00Z");
        when(depthRepository.inventoryHistoricalTickIntervals()).thenReturn(List.of(interval(7L, "token-up", start, end)));
        when(tickRepository.findFirstByTokenIdOrderByEffectiveAtAscIdAsc("token-up")).thenReturn(Optional.of(metadata(
                "token-up",
                start
        )));

        HistoricalTickCoverageService.InventoryResult result = service.inventoryDepthSnapshotCoverage();

        assertThat(result.intervalsCoveredByTickTimeline()).isEqualTo(1);
        assertThat(result.blockersCreated()).isZero();
        verify(coverageRepository, never()).saveAll(any());
    }

    private HistoricalTickCoverageEntity savedCoverage() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<HistoricalTickCoverageEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(coverageRepository).saveAll(captor.capture());
        return captor.getValue().iterator().next();
    }

    private TickSizeMetadataEntity metadata(String tokenId, Instant effectiveAt) {
        return TickSizeMetadataEntity.observed(
                tokenId,
                "market-7",
                new BigDecimal("0.01"),
                effectiveAt,
                effectiveAt,
                TickSizeSource.REST_BOOK
        );
    }

    private MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection interval(
            Long marketId,
            String tokenId,
            Instant start,
            Instant end
    ) {
        return new MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection() {
            @Override
            public Long getMarketId() {
                return marketId;
            }

            @Override
            public String getTokenId() {
                return tokenId;
            }

            @Override
            public Instant getIntervalStartAt() {
                return start;
            }

            @Override
            public Instant getIntervalEndAt() {
                return end;
            }
        };
    }
}
