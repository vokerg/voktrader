package com.vokerg.voktrader.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "voktrader.bots")
public class BotRuntimeProperties {
    private Set<Long> includeIds = new LinkedHashSet<>();

    public Set<Long> getIncludeIds() {
        return includeIds;
    }

    public void setIncludeIds(Set<Long> includeIds) {
        this.includeIds = includeIds == null ? new LinkedHashSet<>() : includeIds;
    }

    public boolean includes(Long botId) {
        return includeIds.isEmpty() || includeIds.contains(botId);
    }
}
