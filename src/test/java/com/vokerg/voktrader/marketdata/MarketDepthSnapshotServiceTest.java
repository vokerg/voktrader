package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.market.MarketRepository;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import com.vokerg.voktrader.trade.TradingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDepthSnapshotServiceTest {

    @Test
    void savesCompactDepthFactsForEachCurrentBook() {
        MarketDepthSnapshotRepository repository = mock(MarketDepthSnapshotRepository.class);
        MarketRepository marketRepository = mock(MarketRepository.class);
        TradingProperties tradingProperties = new TradingProperties();
        tradingProperties.setMaxOrderUsd(new BigDecimal("1.00"));
        tradingProperties.setMaxPriceAgeMs(1500);
        MarketDepthSnapshotService service = new MarketDepthSnapshotService(
                repository,
                marketRepository,
                tradingProperties,
                new MarketDepthSnapshotProperties(new BigDecimal("0.02"))
        );
        when(marketRepository.findByPolymarketMarketId("215")).thenReturn(Optional.empty());
        OrderBookState orderBookState = new OrderBookState();
        Instant bookUpdatedAt = Instant.parse("2026-05-05T17:00:00Z");
        Instant capturedAt = bookUpdatedAt.plusMillis(250);
        orderBookState.update(
                "up-token",
                "Up",
                List.of(
                        new PriceLevelDto("0.49", "2"),
                        new PriceLevelDto("0.46", "10")
                ),
                List.of(
                        new PriceLevelDto("0.51", "1"),
                        new PriceLevelDto("0.52", "3")
                ),
                bookUpdatedAt
        );

        service.saveSnapshots("215", Duration.ofSeconds(90), orderBookState, capturedAt);

        ArgumentCaptor<MarketDepthSnapshotEntity> captor = ArgumentCaptor.forClass(MarketDepthSnapshotEntity.class);
        verify(repository).save(captor.capture());
        MarketDepthSnapshotEntity snapshot = captor.getValue();
        assertEquals(215L, snapshot.getMarketId());
        assertEquals("Up", snapshot.getOutcome());
        assertEquals(new BigDecimal("0.49"), snapshot.getBestBid());
        assertEquals(new BigDecimal("0.51"), snapshot.getBestAsk());
        assertEquals(new BigDecimal("0.02"), snapshot.getSpread());
        assertEquals(new BigDecimal("12"), snapshot.getBidDepth());
        assertEquals(new BigDecimal("4"), snapshot.getAskDepth());
        assertEquals(new BigDecimal("2"), snapshot.getNearBidDepth());
        assertEquals(new BigDecimal("4"), snapshot.getNearAskDepth());
        assertEquals(new BigDecimal("0.50000000"), snapshot.getDepthImbalance());
        assertEquals(new BigDecimal("-0.33333333"), snapshot.getNearDepthImbalance());
        assertEquals(new BigDecimal("1.00"), snapshot.getEstimateBuyUsd());
        assertEquals(new BigDecimal("1.94230769"), snapshot.getEstimateBuyFilledShares());
        assertEquals(new BigDecimal("0.51485149"), snapshot.getEstimateBuyAveragePrice());
        assertEquals(new BigDecimal("0.52"), snapshot.getEstimateBuyWorstPrice());
        assertEquals(2, snapshot.getEstimateBuyLevelsConsumed());
        assertEquals(250L, snapshot.getBookAgeMs());
        assertFalse(snapshot.getStale());
    }
}
