package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import com.vokerg.voktrader.time.TimeMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TickSizeServiceTest {
    private final TickSizeMetadataRepository repository = mock(TickSizeMetadataRepository.class);
    private final List<TickSizeMetadataEntity> history = new ArrayList<>();
    private TickSizeService service;

    @BeforeEach
    void setUp() {
        history.clear();
        service = new TickSizeService(repository);
        when(repository.save(any(TickSizeMetadataEntity.class))).thenAnswer(invocation -> {
            TickSizeMetadataEntity entity = invocation.getArgument(0);
            history.add(entity);
            return entity;
        });
        when(repository.findFirstByTokenIdOrderByEffectiveAtDescIdDesc(anyString()))
                .thenAnswer(invocation -> latest(invocation.getArgument(0), Instant.MAX));
        when(repository.findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(anyString(), any(Instant.class)))
                .thenAnswer(invocation -> latest(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void websocketChangeFromCentToMilliChangesValidationImmediately() {
        Instant initialAt = Instant.parse("2026-07-25T12:00:00Z");
        Instant changedAt = initialAt.plusSeconds(10);

        service.recordRestBook("token-1", "market-1", "0.01", initialAt);
        assertThat(service.validate("token-1", new BigDecimal("0.501")).valid()).isFalse();

        service.recordTickSizeChange("token-1", "market-1", "0.01", "0.001", changedAt);

        assertThat(service.validate("token-1", new BigDecimal("0.501")).valid()).isTrue();
        assertThat(service.requireTickSize("token-1")).isEqualByComparingTo("0.001");
        assertThat(history).hasSize(2);
    }

    @Test
    void replayAndLiveResolveThroughTheSamePersistedTimeline() {
        Instant initialAt = Instant.parse("2026-07-25T12:00:00Z");
        Instant changedAt = initialAt.plusSeconds(10);
        service.recordRestBook("token-1", "market-1", "0.01", initialAt);
        service.recordTickSizeChange("token-1", "market-1", "0.01", "0.001", changedAt);

        assertThat(service.validate("token-1", new BigDecimal("0.501")).valid()).isTrue();

        TimeMachine.runAt(initialAt.plusSeconds(1), () ->
                assertThat(service.validate("token-1", new BigDecimal("0.501")).valid()).isFalse()
        );
        TimeMachine.runAt(changedAt.plusSeconds(1), () ->
                assertThat(service.validate("token-1", new BigDecimal("0.501")).valid()).isTrue()
        );
    }

    private Optional<TickSizeMetadataEntity> latest(String tokenId, Instant at) {
        return history.stream()
                .filter(entity -> tokenId.equals(entity.getTokenId()))
                .filter(entity -> !entity.getEffectiveAt().isAfter(at))
                .max(Comparator.comparing(TickSizeMetadataEntity::getEffectiveAt));
    }
}
