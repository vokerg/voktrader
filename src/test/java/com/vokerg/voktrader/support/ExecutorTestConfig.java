package com.vokerg.voktrader.support;

import com.vokerg.voktrader.bot.BotRuntimeManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class ExecutorTestConfig {
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
}
