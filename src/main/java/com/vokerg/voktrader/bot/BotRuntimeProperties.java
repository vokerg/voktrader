package com.vokerg.voktrader.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "voktrader.bots")
public class BotRuntimeProperties {
    private Set<Long> includeIds = new LinkedHashSet<>();
    private boolean restricted;

    public Set<Long> getIncludeIds() {
        return includeIds;
    }

    public void setIncludeIds(Set<Long> includeIds) {
        this.includeIds = includeIds == null ? new LinkedHashSet<>() : includeIds;
        this.restricted = !this.includeIds.isEmpty();
    }

    public boolean includes(Long botId) {
        return !restricted || includeIds.contains(botId);
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void include(Long botId) {
        if (botId != null) {
            restricted = true;
            includeIds.add(botId);
        }
    }

    public void exclude(Long botId) {
        if (botId != null) {
            includeIds.remove(botId);
        }
    }
}
