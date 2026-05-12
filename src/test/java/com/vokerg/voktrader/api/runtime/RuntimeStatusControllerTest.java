package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotConfigRepository;
import com.vokerg.voktrader.bot.MarketFamily;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.strategy.StrategyProperties;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.OrderLayerProperties;
import com.vokerg.voktrader.trade.TradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeStatusControllerTest {
    @Test
    void statusReturnsTradingRiskExecutorAndBotConfig() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/db?password=secret");
        environment.setActiveProfiles("live-tiny", "live-test");

        TradingProperties trading = new TradingProperties();
        trading.setMode(ExecutionMode.LIVE_TINY);
        trading.setKillSwitchEnabled(false);
        trading.setLiveEnabled(true);
        trading.setMaxOrderUsd(new BigDecimal("1.00"));
        trading.setMaxTradesPerMarket(1);
        trading.setMaxOpenLiveTrades(1);
        trading.setAllowedStrategyIds(Set.of("strategy-v2"));

        ExecutorProperties executor = new ExecutorProperties();
        executor.setEnabled(true);
        executor.setDryRun(false);
        executor.setBaseUrl("http://127.0.0.1:8099");

        OrderLayerProperties orderLayer = new OrderLayerProperties();
        orderLayer.setEnabled(false);

        StrategyV2Properties strategyV2 = new StrategyV2Properties();
        strategyV2.getEngine().setActiveStrategyIds(List.of("ANTI_CHOP_FOK_A"));

        BotConfigEntity botEntity = BotConfigEntity.create("bot", MarketFamily.BTC_5M, "strategy-v2", "deep-research", "ANTI_CHOP_FOK_A", true);
        BotConfigRepository repository = mock(BotConfigRepository.class);
        when(repository.findAllByEnabledTrueOrderByIdAsc()).thenReturn(List.of(botEntity));

        RuntimeStatusController controller = new RuntimeStatusController(
                environment,
                trading,
                executor,
                orderLayer,
                new StrategyProperties("strategy-v2", null, null, null, null, null, null, null, null),
                strategyV2,
                repository
        );

        RuntimeStatusController.RuntimeStatusResponse response = controller.status();

        assertThat(response.activeProfiles()).containsExactly("live-tiny", "live-test");
        assertThat(response.datasourceUrl()).contains("password=****");
        assertThat(response.tradingMode()).isEqualTo("LIVE_TINY");
        assertThat(response.killSwitchEnabled()).isFalse();
        assertThat(response.liveEnabled()).isTrue();
        assertThat(response.maxOrderUsd()).isEqualByComparingTo("1.00");
        assertThat(response.allowedStrategyIds()).containsExactly("strategy-v2");
        assertThat(response.executor().enabled()).isTrue();
        assertThat(response.executor().dryRun()).isFalse();
        assertThat(response.orderLayer().enabled()).isFalse();
        assertThat(response.currentTopLevelActiveStrategy()).isEqualTo("strategy-v2");
        assertThat(response.strategyV2ActiveInnerStrategyIds()).containsExactly("ANTI_CHOP_FOK_A");
        assertThat(response.enabledBots()).singleElement()
                .satisfies(enabledBot -> {
                    assertThat(enabledBot.strategySetId()).isEqualTo("deep-research");
                    assertThat(enabledBot.subStrategyId()).isEqualTo("ANTI_CHOP_FOK_A");
                });
    }
}

