package com.vokerg.voktrader.strategy.v2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyV2PaperConfigTest {
    @ParameterizedTest
    @CsvSource({
            "strategy-v2.paper.yml,cfg_v2_liquidity_momentum_paper,1",
            "strategy-v2.example.yml,cfg_shower_trades_v2,8"
    })
    void configBindsAndValidates(String resource, String activeStrategyId, int strategyCount) {
        contextRunner(resource).run(context -> {
            StrategyV2Properties properties = context.getBean(StrategyV2Properties.class);
            StrategyV2Validator validator = new StrategyV2Validator(properties, new StrategyV2SimulationConfig());

            validator.validateAtStartup();

            assertThat(properties.getEngine().isEnabled()).isTrue();
            assertThat(properties.getEngine().getActiveStrategyIds()).containsExactly(activeStrategyId);
            assertThat(properties.getStrategies()).hasSize(strategyCount);
        });
    }

    private ApplicationContextRunner contextRunner(String resource) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                    yaml.setResources(new ClassPathResource(resource));
                    Properties properties = yaml.getObject();
                    context.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource(resource, properties));
                })
                .withUserConfiguration(Config.class);
    }

    @Configuration
    @EnableConfigurationProperties(StrategyV2Properties.class)
    static class Config {
        @Bean
        StrategyV2SimulationConfig strategyV2SimulationConfig() {
            return new StrategyV2SimulationConfig();
        }
    }
}
