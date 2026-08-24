package com.vokerg.voktrader.support;

import com.vokerg.voktrader.bot.BotRuntimeManager;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.trade.LiveArmService;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class ExecutorTestConfig {
    private static final String TEST_ACCOUNT_ID = "executor-test-account";

    @Bean
    @Primary
    ScriptedExecutorClient scriptedExecutorClient() {
        return new ScriptedExecutorClient();
    }

    @Bean
    @Primary
    BotRuntimeManager botRuntimeManager() {
        return mock(BotRuntimeManager.class);
    }

    @Bean
    ApplicationRunner armScriptedLiveExecutor(
            TradingProperties tradingProperties,
            ExecutorProperties executorProperties,
            LiveArmService liveArmService
    ) {
        return args -> {
            if (tradingProperties.getMode() != ExecutionMode.LIVE) {
                return;
            }
            executorProperties.setEnabled(true);
            executorProperties.setDryRun(false);
            executorProperties.setApiToken("executor-test-token");
            tradingProperties.setExpectedAccountId(TEST_ACCOUNT_ID);
            liveArmService.arm(TEST_ACCOUNT_ID);
        };
    }
}
