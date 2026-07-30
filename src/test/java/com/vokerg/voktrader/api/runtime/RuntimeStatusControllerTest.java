package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotConfigRepository;
import com.vokerg.voktrader.bot.MarketFamily;
import com.vokerg.voktrader.executor.ExecutorCapabilityService;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.strategy.StrategyProperties;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import com.vokerg.voktrader.trade.LiveArmService;
import com.vokerg.voktrader.trade.OrderLayerProperties;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeStatusControllerTest {
    @Test
    void statusReturnsTradingRiskExecutorArmAndBotConfig() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/db?password=secret");
        environment.setActiveProfiles("live", "live-test");

        TradingProperties trading = new TradingProperties();
        trading.setMode(ExecutionMode.LIVE);
        trading.setKillSwitchEnabled(false);
        trading.setLiveEnabled(true);
        trading.setExpectedAccountId("0xexpected");
        trading.setLiveArmTtl(Duration.ofMinutes(15));
        trading.setMaxOrderUsd(new BigDecimal("1.00"));
        trading.setMaxTradesPerMarket(1);
        trading.setMaxOpenLiveTrades(1);
        trading.setAllowedStrategyIds(Set.of("strategy-v2"));

        ExecutorProperties executor = new ExecutorProperties();
        executor.setEnabled(true);
        executor.setDryRun(false);
        executor.setApiToken("non-default-token");
        executor.setBaseUrl("http://127.0.0.1:8099");
        ExecutorCapabilityService capabilityService = mock(ExecutorCapabilityService.class);
        when(capabilityService.report()).thenReturn(new ExecutorCapabilityService.ExecutorCapabilityReport(
                true,
                true,
                "executor-api-v1",
                "0.3.0",
                "py-clob-client-v2",
                "1.1.0",
                List.of("FOK", "FAK", "GTC", "GTD"),
                List.of()
        ));
        LiveArmService liveArmService = new LiveArmService(trading, executor);

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
                capabilityService,
                liveArmService,
                orderLayer,
                new StrategyProperties("strategy-v2", null, null, null, null, null, null, null, null),
                strategyV2,
                repository
        );

        RuntimeStatusController.RuntimeStatusResponse response = controller.status();

        assertThat(response.activeProfiles()).containsExactly("live", "live-test");
        assertThat(response.datasourceUrl()).contains("password=****");
        assertThat(response.tradingMode()).isEqualTo("LIVE");
        assertThat(response.killSwitchEnabled()).isFalse();
        assertThat(response.liveEnabled()).isTrue();
        assertThat(response.liveArm().capabilityReady()).isTrue();
        assertThat(response.liveArm().executorTokenConfigured()).isTrue();
        assertThat(response.liveArm().expectedAccountConfigured()).isTrue();
        assertThat(response.liveArm().armed()).isFalse();
        assertThat(response.liveArm().entryAllowed()).isFalse();
        assertThat(response.liveArm().entryBlockers()).containsExactly("live arm is not active");
        assertThat(response.maxOrderUsd()).isEqualByComparingTo("1.00");
        assertThat(response.allowedStrategyIds()).containsExactly("strategy-v2");
        assertThat(response.executor().enabled()).isTrue();
        assertThat(response.executor().dryRun()).isFalse();
        assertThat(response.executor().capabilities().compatible()).isTrue();
        assertThat(response.executor().capabilities().protocolVersion()).isEqualTo("executor-api-v1");
        assertThat(response.executor().capabilities().sdkVersion()).isEqualTo("1.1.0");
        assertThat(response.executor().capabilities().blockers()).isEmpty();
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
