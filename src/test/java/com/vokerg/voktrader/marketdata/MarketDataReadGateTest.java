package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataReadGateTest {

    @Test
    void priceAndBookReadsAreHiddenWhileSupervisorIsPaused() {
        AtomicBoolean readable = new AtomicBoolean(false);
        LatestPriceState prices = new LatestPriceState(readable::get);
        OrderBookState books = new OrderBookState(readable::get);

        prices.update("up-token", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"), Instant.EPOCH);
        books.update(
                "up-token",
                "Up",
                List.of(new PriceLevelDto("0.49", "2")),
                List.of(new PriceLevelDto("0.51", "3")),
                Instant.EPOCH
        );

        assertTrue(prices.byTokenId("up-token").isEmpty());
        assertTrue(prices.allByTokenId().isEmpty());
        assertTrue(books.byTokenId("up-token").isEmpty());
        assertTrue(books.allByTokenId().isEmpty());

        readable.set(true);

        assertTrue(prices.byTokenId("up-token").isPresent());
        assertTrue(books.byTokenId("up-token").isPresent());
    }
}
