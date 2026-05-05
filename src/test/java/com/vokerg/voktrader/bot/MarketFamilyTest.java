package com.vokerg.voktrader.bot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketFamilyTest {
    @Test
    void buildsDeterministicSlugsForConfiguredFamilies() {
        assertEquals("btc-updown-5m-1710000000", MarketFamily.BTC_5M.slugForStartEpoch(1710000000L));
        assertEquals("eth-updown-15m-1710000000", MarketFamily.ETH_15M.slugForStartEpoch(1710000000L));
        assertEquals("sol-updown-5m-1710000000", MarketFamily.SOL_5M.slugForStartEpoch(1710000000L));
    }

    @Test
    void createsCurrentAndLookAheadSlugsOnIntervalBoundary() {
        assertEquals(
                List.of("btc-updown-5m-300", "btc-updown-5m-600", "btc-updown-5m-900"),
                MarketFamily.BTC_5M.candidateSlugs(Instant.ofEpochSecond(305), 2)
        );
    }

    @Test
    void acceptsCommonAssetNames() {
        assertEquals(MarketFamily.BTC_5M, MarketFamily.fromCodes("bitcoin", "5m"));
        assertEquals(MarketFamily.ETH_15M, MarketFamily.fromCodes("ethereum", "15m"));
        assertEquals(MarketFamily.SOL_5M, MarketFamily.fromCodes("solana", "5m"));
    }
}
