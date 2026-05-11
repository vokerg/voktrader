package com.vokerg.voktrader.strategy.v2;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Component
public class StrategyV2OverrideParser {
    private final StrategyV2Validator validator;

    public StrategyV2OverrideParser(StrategyV2Validator validator) {
        this.validator = validator;
    }

    public StrategyV2Properties parse(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new IllegalArgumentException("Strategy YAML override must not be blank");
        }

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ByteArrayResource(yamlText.getBytes(StandardCharsets.UTF_8)));
        Properties props = yaml.getObject();
        if (props == null || props.isEmpty()) {
            throw new IllegalArgumentException("Strategy YAML override did not produce any properties");
        }

        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new PropertiesPropertySource("strategy-v2-override", props));
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);
        if (resolver.getProperty("strategy-v2.engine.enabled") == null) {
            throw new IllegalArgumentException("Strategy YAML override must contain a strategy-v2 root");
        }

        StrategyV2Properties parsed = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(propertySources))
                .bind("strategy-v2", Bindable.of(StrategyV2Properties.class))
                .orElseGet(StrategyV2Properties::new);
        validator.validate(parsed);
        return parsed;
    }
}
