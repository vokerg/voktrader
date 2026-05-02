package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.bot.dto.BotConfigView;
import com.vokerg.voktrader.bot.dto.BotCreateRequest;
import com.vokerg.voktrader.bot.dto.BotSwitchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bots")
@ConditionalOnProperty(name = "voktrader.bots.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BotController {
    private final BotConfigService configService;
    private final BotRuntimeManager runtimeManager;

    @GetMapping
    public List<BotConfigView> list() {
        return configService.list().stream().map(BotConfigView::from).toList();
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
}
