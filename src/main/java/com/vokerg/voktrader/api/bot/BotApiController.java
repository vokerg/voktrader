package com.vokerg.voktrader.api.bot;

import com.vokerg.voktrader.api.bot.dto.BotConfigResponse;
import com.vokerg.voktrader.api.bot.dto.BotCreateRequest;
import com.vokerg.voktrader.api.bot.dto.BotUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bots")
public class BotApiController {
    private final BotApiService botApiService;

    public BotApiController(BotApiService botApiService) {
        this.botApiService = botApiService;
    }

    @GetMapping
    public List<BotConfigResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String marketFamily,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String strategyId
    ) {
        return botApiService.list(status, enabled, marketFamily, asset, interval, strategyId);
    }

    @GetMapping("/{id}")
    public BotConfigResponse get(@PathVariable Long id) {
        return botApiService.get(id);
    }

    @PostMapping
    public BotConfigResponse create(@RequestBody BotCreateRequest request) {
        return botApiService.create(
                request.name(),
                request.marketFamily(),
                request.asset(),
                request.interval(),
                request.strategyId(),
                request.enabled()
        );
    }

    @PatchMapping("/{id}")
    public BotConfigResponse update(@PathVariable Long id, @RequestBody BotUpdateRequest request) {
        return botApiService.update(
                id,
                request.marketFamily(),
                request.asset(),
                request.interval(),
                request.strategyId(),
                request.enabled()
        );
    }

    @PostMapping("/{id}/pause")
    public BotConfigResponse pause(@PathVariable Long id) {
        return botApiService.pause(id);
    }

    @PostMapping("/{id}/resume")
    public BotConfigResponse resume(@PathVariable Long id) {
        return botApiService.resume(id);
    }

    @PostMapping("/{id}/roll")
    public BotConfigResponse roll(@PathVariable Long id) {
        return botApiService.roll(id);
    }
}
