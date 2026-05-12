package com.vokerg.voktrader.api.bot;

import com.vokerg.voktrader.api.bot.dto.BotConfigResponse;
import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotConfigService;
import com.vokerg.voktrader.bot.BotRuntime;
import com.vokerg.voktrader.bot.BotRuntimeManager;
import com.vokerg.voktrader.bot.BotStatus;
import com.vokerg.voktrader.bot.MarketFamily;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "voktrader.bots", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BotApiService {
    private final BotConfigService configService;
    private final BotRuntimeManager runtimeManager;

    public BotApiService(BotConfigService configService, BotRuntimeManager runtimeManager) {
        this.configService = configService;
        this.runtimeManager = runtimeManager;
    }

    public List<BotConfigResponse> list(String status, Boolean enabled, String marketFamily, String asset, String interval, String strategyId) {
        BotStatus parsedStatus = parseStatus(status);
        MarketFamily parsedFamily = marketFamily == null && asset == null && interval == null
                ? null
                : MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        Set<Long> activeRuntimeIds = activeRuntimeIds();
        return configService.list(parsedStatus, enabled, parsedFamily, strategyId).stream()
                .map(bot -> BotConfigResponse.from(bot, activeRuntimeIds.contains(bot.getId())))
                .toList();
    }

    public BotConfigResponse get(Long id) {
        Set<Long> activeRuntimeIds = activeRuntimeIds();
        return configService.list().stream()
                .filter(bot -> bot.getId().equals(id))
                .findFirst()
                .map(bot -> BotConfigResponse.from(bot, activeRuntimeIds.contains(bot.getId())))
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot id: " + id));
    }

    public BotConfigResponse create(String name, String marketFamily, String asset, String interval, String strategyId, Boolean enabled) {
        MarketFamily family = MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        boolean actualEnabled = enabled == null || enabled;
        String resolvedName = name == null || name.isBlank()
                ? family.name().toLowerCase(Locale.ROOT) + "-" + strategyId
                : name.trim();
        BotConfigEntity created = configService.create(resolvedName, family, strategyId, actualEnabled);
        runtimeManager.restart(created.getId(), "created via api");
        return BotConfigResponse.from(created, actualEnabled);
    }

    public BotConfigResponse update(Long id, String marketFamily, String asset, String interval, String strategyId, Boolean enabled) {
        MarketFamily family = marketFamily == null && asset == null && interval == null
                ? null
                : MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        BotConfigEntity updated = configService.switchConfig(id, family, strategyId, enabled);
        runtimeManager.restart(id, "updated via api");
        return BotConfigResponse.from(updated, updated.isEnabled());
    }

    public BotConfigResponse pause(Long id) {
        BotConfigEntity updated = configService.pause(id);
        runtimeManager.restart(id, "paused via api");
        return BotConfigResponse.from(updated, false);
    }

    public BotConfigResponse resume(Long id) {
        BotConfigEntity updated = configService.resume(id);
        runtimeManager.restart(id, "resumed via api");
        return BotConfigResponse.from(updated, true);
    }

    public BotConfigResponse roll(Long id) {
        runtimeManager.restart(id, "manual roll via api");
        return list(null, null, null, null, null, null).stream()
                .filter(bot -> bot.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot id: " + id));
    }

    private BotStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return BotStatus.valueOf(status.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private Set<Long> activeRuntimeIds() {
        return runtimeManager.runtimes().stream()
                .map(BotRuntime::botId)
                .collect(Collectors.toSet());
    }
}
