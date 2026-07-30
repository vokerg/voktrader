package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import com.vokerg.voktrader.time.TimeMachine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TickSizeReplayCoverageGuardTest {
    private final TickSizeMetadataRepository repository = mock(TickSizeMetadataRepository.class);
    private final HistoricalTickCoverageService coverageService = mock(HistoricalTickCoverageService.class);
    private final TickSizeService service = new TickSizeService(repository, coverageService);

    @Test
    void timeMachineLookupRequiresTokenCoverageBeforeReadingMetadata() {
        Instant at = Instant.parse("2026-07-18T10:10:00Z");
        when(repository.findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc("token-up", at))
                .thenReturn(Optional.of(metadata("token-up", at.minusSeconds(60))));

        TimeMachine.runAt(at, () -> assertThat(service.requireTickSize("token-up")).isEqualByComparingTo("0.001"));

        verify(coverageService).assertTokenReplayReady("token-up", at);
    }

    @Test
    void datasetAwareHistoricalRunRequiresResolvedDatasetCoverage() {
        Instant at = Instant.parse("2026-07-18T10:10:00Z");
        when(repository.findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc("token-up", at))
                .thenReturn(Optional.of(metadata("token-up", at.minusSeconds(60))));
        AtomicBoolean executed = new AtomicBoolean(false);

        service.runWithHistoricalTicks(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                List.of("token-up"),
                at,
                () -> {
                    assertThat(service.requireTickSize("token-up")).isEqualByComparingTo("0.001");
                    executed.set(true);
                }
        );

        assertThat(executed).isTrue();
        verify(coverageService).assertReplayReady(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                "token-up",
                at
        );
    }

    @Test
    void blockedDatasetCoveragePreventsReplayAction() {
        Instant at = Instant.parse("2026-07-18T10:10:00Z");
        doThrow(new IllegalStateException("historical replay is blocked"))
                .when(coverageService)
                .assertReplayReady(HistoricalTickDatasetType.PRICE_SNAPSHOT, 42L, "token-up", at);
        AtomicBoolean executed = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.runWithHistoricalTicks(
                HistoricalTickDatasetType.PRICE_SNAPSHOT,
                42L,
                List.of("token-up"),
                at,
                () -> executed.set(true)
        )).hasMessageContaining("historical replay is blocked");
        assertThat(executed).isFalse();
    }

    private TickSizeMetadataEntity metadata(String tokenId, Instant effectiveAt) {
        return TickSizeMetadataEntity.observed(
                tokenId,
                "protocol-market-42",
                new BigDecimal("0.001"),
                effectiveAt,
                effectiveAt,
                TickSizeSource.REST_BOOK
        );
    }
}
