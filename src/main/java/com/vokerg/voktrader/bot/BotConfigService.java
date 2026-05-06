package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.strategy.StrategyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotConfigService {
    private static final String DEFAULT_BOT_NAME = "default-btc-updown";

    private final BotConfigRepository repository;
    private final MarketSelectionProperties marketSelectionProperties;
    private final StrategyProperties strategyProperties;

    @Transactional
    public void seedDefaultIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        MarketFamily family = MarketFamily.fromCodes("BTC", marketSelectionProperties.intervalOrDefault());
        String strategyId = strategyProperties.activeOrDefault();
        BotConfigEntity defaultBot = BotConfigEntity.create(DEFAULT_BOT_NAME, family, strategyId, true);
        repository.save(defaultBot);
        log.info("Seeded default bot config: name={} family={} strategy={}", defaultBot.getName(), family, strategyId);
    }

    @Transactional(readOnly = true)
    public List<BotConfigEntity> list() {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional
    public BotConfigEntity create(String name, MarketFamily family, String strategyId, boolean enabled) {
        String normalizedName = name == null ? null : name.trim();
        return repository.findByName(normalizedName)
                .map(existing -> {
                    existing.switchTo(family, strategyId, enabled);
                    log.info(
                            "Updated existing bot config during create: id={} name={} family={} strategy={} enabled={}",
                            existing.getId(),
                            existing.getName(),
                            existing.getMarketFamily(),
                            existing.getStrategyId(),
                            existing.isEnabled()
                    );
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(BotConfigEntity.create(normalizedName, family, strategyId, enabled)));
    }

    @Transactional
    public BotConfigEntity switchConfig(Long id, MarketFamily family, String strategyId, Boolean enabled) {
        BotConfigEntity bot = getRequired(id);
        bot.switchTo(family, strategyId, enabled);
        return repository.save(bot);
    }

    @Transactional
    public BotConfigEntity pause(Long id) {
        BotConfigEntity bot = getRequired(id);
        bot.pause();
        return repository.save(bot);
    }

    @Transactional
    public BotConfigEntity resume(Long id) {
        BotConfigEntity bot = getRequired(id);
        bot.resume();
        return repository.save(bot);
    }

    @Transactional
    public void markError(Long id, Throwable throwable) {
        repository.findById(id).ifPresent(bot -> {
            bot.markError(throwable);
            repository.save(bot);
        });
    }

    private BotConfigEntity getRequired(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown bot id: " + id));
    }
}
