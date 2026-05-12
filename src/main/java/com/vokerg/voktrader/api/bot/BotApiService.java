package com.vokerg.voktrader.api.bot;

import com.vokerg.voktrader.api.bot.dto.BotConfigResponse;
import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotConfigService;
import com.vokerg.voktrader.bot.BotRuntime;
import com.vokerg.voktrader.bot.BotRuntimeManager;
import com.vokerg.voktrader.bot.BotRuntimeProperties;
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
    private final BotRuntimeProperties runtimeProperties;

    public BotApiService(
            BotConfigService configService,
            BotRuntimeManager runtimeManager,
            BotRuntimeProperties runtimeProperties
    ) {
        this.configService = configService;
        this.runtimeManager = runtimeManager;
        this.runtimeProperties = runtimeProperties;
    }

    public List<BotConfigResponse> list(String status, Boolean enabled, String marketFamily, String asset, String interval, String strategyId, String subStrategyId) {
        BotStatus parsedStatus = parseStatus(status);
        MarketFamily parsedFamily = marketFamily == null && asset == null && interval == null
                ? null
                : MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        Set<Long> activeRuntimeIds = activeRuntimeIds();
        return configService.list(parsedStatus, enabled, parsedFamily, strategyId, subStrategyId).stream()
                .map(bot -> response(bot, activeRuntimeIds.contains(bot.getId())))
                .toList();
    }

    public BotConfigResponse get(Long id) {
        Set<Long> activeRuntimeIds = activeRuntimeIds();
        return configService.list().stream()
                .filter(bot -> bot.getId().equals(id))
                .findFirst()
                .map(bot -> response(bot, activeRuntimeIds.contains(bot.getId())))
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot id: " + id));
    }

    public BotConfigResponse create(String name, String marketFamily, String asset, String interval, String strategyId, String strategySetId, String subStrategyId, Boolean enabled) {
        MarketFamily family = MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        boolean actualEnabled = enabled == null || enabled;
        String resolvedName = name == null || name.isBlank()
                ? family.name().toLowerCase(Locale.ROOT) + "-" + strategyId + (strategySetId == null || strategySetId.isBlank() ? "" : "-" + strategySetId)
                : name.trim();
        BotConfigEntity created = configService.create(resolvedName, family, strategyId, strategySetId, subStrategyId, actualEnabled);
        runtimeManager.restart(created.getId(), "created via api");
        return response(created, runtimeManager.runtimes().stream().anyMatch(runtime -> runtime.botId().equals(created.getId())));
    }

    public BotConfigResponse update(Long id, String marketFamily, String asset, String interval, String strategyId, String strategySetId, String subStrategyId, Boolean enabled) {
        MarketFamily family = marketFamily == null && asset == null && interval == null
                ? null
                : MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        BotConfigEntity updated = configService.switchConfig(id, family, strategyId, strategySetId, subStrategyId, enabled);
        runtimeManager.restart(id, "updated via api");
        return response(updated, runtimeManager.runtimes().stream().anyMatch(runtime -> runtime.botId().equals(id)));
    }

    public List<BotConfigResponse> killAll() {
        List<BotConfigEntity> updated = configService.pauseAll();
        runtimeManager.stopAll("kill-all via api");
        return updated.stream()
                .map(bot -> response(bot, false))
                .toList();
    }

    public BotConfigResponse pause(Long id) {
        BotConfigEntity updated = configService.pause(id);
        runtimeManager.restart(id, "paused via api");
        return response(updated, false);
    }

    public BotConfigResponse resume(Long id) {
        BotConfigEntity updated = configService.resume(id);
        runtimeManager.restart(id, "resumed via api");
        return response(updated, runtimeManager.runtimes().stream().anyMatch(runtime -> runtime.botId().equals(id)));
    }

    public BotConfigResponse includeRuntime(Long id) {
        BotConfigEntity bot = getEntity(id);
        runtimeProperties.include(id);
        runtimeManager.reload("runtime include via api");
        return response(bot, runtimeManager.runtimes().stream().anyMatch(runtime -> runtime.botId().equals(id)));
    }

    public BotConfigResponse excludeRuntime(Long id) {
        BotConfigEntity bot = getEntity(id);
        runtimeProperties.exclude(id);
        runtimeManager.reload("runtime exclude via api");
        return response(bot, false);
    }

    public BotConfigResponse roll(Long id) {
        runtimeManager.restart(id, "manual roll via api");
        return list(null, null, null, null, null, null, null).stream()
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

    private BotConfigEntity getEntity(Long id) {
        return configService.list().stream()
                .filter(bot -> bot.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot id: " + id));
    }

    private BotConfigResponse response(BotConfigEntity entity, boolean runtimeActive) {
        return BotConfigResponse.from(
                entity,
                runtimeActive,
                runtimeProperties.includes(entity.getId()),
                runtimeProperties.isRestricted()
        );
    }
}

