package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.bot.dto.BotConfigView;
import com.vokerg.voktrader.bot.dto.BotCreateRequest;
import com.vokerg.voktrader.bot.dto.BotSwitchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
public class BotController {
    private final BotConfigService configService;
    private final BotRuntimeManager runtimeManager;

    @GetMapping
    public List<BotConfigView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String marketFamily,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String strategyId
    ) {
        BotStatus parsedStatus = parseStatus(status);
        MarketFamily family = marketFamily == null && asset == null && interval == null
                ? null
                : MarketFamily.fromNameOrCodes(marketFamily, asset, interval);
        return configService.list(parsedStatus, enabled, family, strategyId).stream().map(BotConfigView::from).toList();
    }

    @PostMapping
    public BotConfigView create(@RequestBody BotCreateRequest request) {
        MarketFamily family = MarketFamily.fromNameOrCodes(request.marketFamily(), request.asset(), request.interval());
        boolean enabled = request.enabled() == null || request.enabled();
        String name = request.name() == null || request.name().isBlank()
                ? family.name().toLowerCase() + "-" + request.strategyId()
                : request.name().trim();
        BotConfigEntity created = configService.create(name, family, request.strategyId(), enabled);
        runtimeManager.restart(created.getId(), "created via api");
        return BotConfigView.from(created);
    }

    @PatchMapping("/{id}")
    public BotConfigView switchBot(@PathVariable Long id, @RequestBody BotSwitchRequest request) {
        MarketFamily family = request.marketFamily() == null && request.asset() == null && request.interval() == null
                ? null
                : MarketFamily.fromNameOrCodes(request.marketFamily(), request.asset(), request.interval());
        BotConfigEntity updated = configService.switchConfig(id, family, request.strategyId(), request.enabled());
        runtimeManager.restart(id, "switched via api");
        return BotConfigView.from(updated);
    }

    @PostMapping("/{id}/pause")
    public BotConfigView pause(@PathVariable Long id) {
        BotConfigEntity updated = configService.pause(id);
        runtimeManager.restart(id, "paused via api");
        return BotConfigView.from(updated);
    }

    @PostMapping("/{id}/resume")
    public BotConfigView resume(@PathVariable Long id) {
        BotConfigEntity updated = configService.resume(id);
        runtimeManager.restart(id, "resumed via api");
        return BotConfigView.from(updated);
    }

    @PostMapping("/{id}/roll")
    public BotConfigView roll(@PathVariable Long id) {
        runtimeManager.restart(id, "manual roll via api");
        return configService.list().stream()
                .filter(bot -> bot.getId().equals(id))
                .findFirst()
                .map(BotConfigView::from)
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot id: " + id));
    }

    private BotStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return BotStatus.valueOf(status.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
