package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.MarketPersistenceService;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.resolution.MarketResolutionService;
import com.vokerg.voktrader.strategy.StrategyRegistry;
import com.vokerg.voktrader.strategy.TradingStrategy;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class BotRuntimeManager implements CommandLineRunner {
    private final BotConfigService configService;
    private final BotConfigRepository configRepository;
    private final GammaClient gammaClient;
    private final ClobClient clobClient;
    private final PolymarketWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final MarketSelectionProperties marketSelectionProperties;
    private final MarketPersistenceService marketPersistenceService;
    private final MarketResolutionService marketResolutionService;
    private final StrategyRegistry strategyRegistry;

    private final Map<Long, BotRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public void run(String... args) {
        configService.seedDefaultIfEmpty();
        reload("startup");
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    public void reloadEnabledBots() {
        reload("scheduled refresh");
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void ensureMarketsTracked() {
        runtimes.values().forEach(BotRuntime::ensureMarketIsTracked);
    }

    @Scheduled(fixedRateString = "${voktrader.strategy.tick-ms:1000}")
    public void tickStrategies() {
        runtimes.values().forEach(runtime -> {
            if (runtime.marketExpired()) {
                return;
            }
            TradingStrategy strategy = strategyRegistry.strategy(runtime.config().getStrategyId());
            try {
                BotRuntimeContextHolder.runWith(runtime.context(), strategy::tick);
            } catch (Exception ex) {
                log.error("Strategy tick failed botId={} strategyId={}", runtime.botId(), strategy.id(), ex);
                configService.markError(runtime.botId(), ex);
            }
        });
    }

    public synchronized void reload(String reason) {
        Set<Long> desired = new HashSet<>();
        for (BotConfigEntity config : configRepository.findAllByEnabledTrueOrderByIdAsc()) {
            desired.add(config.getId());
            runtimes.computeIfAbsent(config.getId(), ignored -> {
                BotRuntime runtime = newRuntime(config);
                runtime.start(reason);
                return runtime;
            });
        }
        for (Long botId : new HashSet<>(runtimes.keySet())) {
            if (!desired.contains(botId)) {
                BotRuntime runtime = runtimes.remove(botId);
                if (runtime != null) {
                    runtime.shutdown();
                }
            }
        }
    }

    public synchronized void restart(Long botId, String reason) {
        BotRuntime existing = runtimes.remove(botId);
        if (existing != null) {
            existing.shutdown();
        }
        configRepository.findById(botId)
                .filter(BotConfigEntity::isEnabled)
                .ifPresent(config -> {
                    BotRuntime runtime = newRuntime(config);
                    runtimes.put(botId, runtime);
                    runtime.start(reason);
                });
    }

    public List<BotRuntime> runtimes() {
        return List.copyOf(runtimes.values());
    }

    private BotRuntime newRuntime(BotConfigEntity config) {
        return new BotRuntime(
                config,
                gammaClient,
                clobClient,
                webSocketClient,
                objectMapper,
                marketSelectionProperties,
                marketPersistenceService,
                marketResolutionService
        );
    }

    @PreDestroy
    public void shutdown() {
        runtimes.values().forEach(BotRuntime::shutdown);
        runtimes.clear();
    }
}
