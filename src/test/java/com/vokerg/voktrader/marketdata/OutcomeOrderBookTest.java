package com.vokerg.voktrader.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeOrderBookTest {

    @Test
    void estimatesBuyAcrossAskLevels() {
        OutcomeOrderBook book = new OutcomeOrderBook(
                "up-token",
                "Up",
                List.of(),
                List.of(
                        new OrderBookLevel(new BigDecimal("0.60"), new BigDecimal("2")),
                        new OrderBookLevel(new BigDecimal("0.50"), new BigDecimal("1"))
                ),
                Instant.now()
        );

        FillEstimate estimate = book.estimateBuyUsd(new BigDecimal("1.10"));

        assertTrue(estimate.complete());
        assertEquals(new BigDecimal("2.00000000"), estimate.filledShares());
        assertEquals(new BigDecimal("1.10"), estimate.notionalUsd());
        assertEquals(new BigDecimal("0.55000000"), estimate.averagePrice());
        assertEquals(new BigDecimal("0.60"), estimate.worstPrice());
        assertEquals(2, estimate.levelsConsumed());
    }

    @Test
    void marksBuyIncompleteWhenAskDepthIsInsufficient() {
        OutcomeOrderBook book = new OutcomeOrderBook(
                "up-token",
                "Up",
                List.of(),
                List.of(new OrderBookLevel(new BigDecimal("0.50"), new BigDecimal("1"))),
                Instant.now()
        );

        FillEstimate estimate = book.estimateBuyUsd(new BigDecimal("2.00"));

        assertFalse(estimate.complete());
        assertEquals(new BigDecimal("1"), estimate.filledShares());
        assertEquals(new BigDecimal("0.50"), estimate.notionalUsd());
        assertEquals(new BigDecimal("1.50"), estimate.unspentUsd());
    }

    @Test
    void estimatesSellAcrossBidLevels() {
        OutcomeOrderBook book = new OutcomeOrderBook(
                "up-token",
                "Up",
                List.of(
                        new OrderBookLevel(new BigDecimal("0.55"), new BigDecimal("2")),
                        new OrderBookLevel(new BigDecimal("0.60"), new BigDecimal("1"))
                ),
                List.of(),
                Instant.now()
        );

        FillEstimate estimate = book.estimateSellShares(new BigDecimal("2"));

        assertTrue(estimate.complete());
        assertEquals(new BigDecimal("2"), estimate.filledShares());
        assertEquals(new BigDecimal("1.15"), estimate.notionalUsd());
        assertEquals(new BigDecimal("0.57500000"), estimate.averagePrice());
        assertEquals(new BigDecimal("0.55"), estimate.worstPrice());
        assertEquals(2, estimate.levelsConsumed());
    }
}
