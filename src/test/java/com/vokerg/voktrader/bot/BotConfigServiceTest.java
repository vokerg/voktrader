package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.strategy.StrategyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotConfigServiceTest {
    private final BotConfigRepository repository = mock(BotConfigRepository.class);
    private final BotConfigService service = new BotConfigService(
            repository,
            new MarketSelectionProperties("5m", 60L, 300L, 0L),
            new StrategyProperties(null, null, null, null, null, null, null, null, null)
    );

    @Test
    void filtersOverviewByStatusFamilyAndStrategy() {
        BotConfigEntity runningV2 = BotConfigEntity.create("v2-btc", MarketFamily.BTC_5M, "strategy-v2", "deep-research", "ANTI_CHOP_FOK_A", true);
        BotConfigEntity pausedV2 = BotConfigEntity.create("v2-eth", MarketFamily.ETH_5M, "strategy-v2", "paper", "SNIPER_FOK_A", false);
        BotConfigEntity runningOther = BotConfigEntity.create("other-btc", MarketFamily.BTC_5M, "order-book-liquidity", null, null, true);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(runningV2, pausedV2, runningOther));

        List<BotConfigEntity> result = service.list(BotStatus.RUNNING, true, MarketFamily.BTC_5M, "Strategy-V2", "anti_chop_fok_a");

        assertEquals(List.of(runningV2), result);
    }

    @Test
    void filtersOverviewByPausedStatus() {
        BotConfigEntity running = BotConfigEntity.create("running", MarketFamily.BTC_5M, "strategy-v2", null, null, true);
        BotConfigEntity paused = BotConfigEntity.create("paused", MarketFamily.BTC_5M, "strategy-v2", null, null, false);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(running, paused));

        List<BotConfigEntity> result = service.list(BotStatus.PAUSED, null, null, null, null);

        assertEquals(List.of(paused), result);
    }
}

