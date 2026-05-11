package com.vokerg.voktrader.market;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketPersistenceServiceTest {

    private final MarketRepository repository = mock(MarketRepository.class);
    private final MarketPersistenceService service = new MarketPersistenceService(repository);

    @Test
    void retriesDuplicateInsertByUpdatingExistingMarket() {
        GammaMarketDto market = new GammaMarketDto(
                "2224267",
                "Bitcoin Up or Down?",
                "condition-1",
                "bitcoin-up-or-down",
                Instant.parse("2026-05-11T12:00:00Z"),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
        MarketEntity existing = new MarketEntity();
        existing.setPolymarketMarketId("2224267");
        existing.setFirstSeenAt(Instant.parse("2026-05-11T11:00:00Z"));

        when(repository.findByPolymarketMarketId("2224267"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(MarketEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate market"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MarketEntity saved = service.saveOrUpdate(market);

        assertSame(existing, saved);
        assertEquals("condition-1", saved.getConditionId());
        assertEquals("Bitcoin Up or Down?", saved.getQuestion());
        assertEquals("bitcoin-up-or-down", saved.getSlug());
        assertEquals(MarketTrackingStatus.TRACKING, saved.getTrackingStatus());
        assertEquals(MarketResolutionStatus.UNRESOLVED, saved.getResolutionStatus());
        assertEquals(0, saved.getResolutionAttempts());

        ArgumentCaptor<MarketEntity> captor = ArgumentCaptor.forClass(MarketEntity.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        assertEquals("2224267", captor.getAllValues().get(0).getPolymarketMarketId());
        assertSame(existing, captor.getAllValues().get(1));
    }
}
