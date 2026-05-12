package com.vokerg.voktrader.strategy.v2;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StrategyV2SetCatalog {
    private final ResourceLoader resourceLoader;
    private final StrategyV2OverrideParser parser;
    private final Map<String, String> resourcesById;
    private final Map<String, StrategyV2Properties> cache = new ConcurrentHashMap<>();

    public StrategyV2SetCatalog(ResourceLoader resourceLoader, StrategyV2OverrideParser parser) {
        this.resourceLoader = resourceLoader;
        this.parser = parser;
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("paper", "classpath:strategy-v2.paper.yml");
        resources.put("deep-research", "classpath:strategy-v2.deep-research.yml");
        this.resourcesById = Map.copyOf(resources);
    }

    public Optional<StrategyV2Properties> propertiesFor(String setId) {
        if (setId == null || setId.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(setId);
        if (!resourcesById.containsKey(normalized)) {
            throw new IllegalArgumentException("Unknown Strategy V2 set id '" + setId + "'. Available set ids: " + resourcesById.keySet());
        }
        return Optional.of(cache.computeIfAbsent(normalized, this::load));
    }

    public Set<String> setIds() {
        return resourcesById.keySet();
    }

    private StrategyV2Properties load(String setId) {
        String location = resourcesById.get(setId);
        Resource resource = resourceLoader.getResource(location);
        try {
            return parser.parse(resource.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not load Strategy V2 set id '" + setId + "' from " + location, e);
        }
    }

    private String normalize(String setId) {
        return setId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

