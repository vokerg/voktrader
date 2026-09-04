package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.bot.BotConfigEntity;
import com.vokerg.voktrader.bot.BotConfigRepository;
import com.vokerg.voktrader.executor.ExecutorCapabilityService;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.strategy.StrategyProperties;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import com.vokerg.voktrader.trade.LiveArmService;
import com.vokerg.voktrader.trade.OrderLayerProperties;
import com.vokerg.voktrader.trade.TradingProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeStatusController {
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?i)(password=)[^;&]+");

    private final Environment environment;
    private final TradingProperties tradingProperties;
    private final ExecutorProperties executorProperties;
    private final ExecutorCapabilityService executorCapabilityService;
    private final LiveArmService liveArmService;
    private final OrderLayerProperties orderLayerProperties;
    private final StrategyProperties strategyProperties;
    private final StrategyV2Properties strategyV2Properties;
    private final BotConfigRepository botConfigRepository;

    public RuntimeStatusController(
            Environment environment,
            TradingProperties tradingProperties,
            ExecutorProperties executorProperties,
            ExecutorCapabilityService executorCapabilityService,
            LiveArmService liveArmService,
            OrderLayerProperties orderLayerProperties,
            StrategyProperties strategyProperties,
            StrategyV2Properties strategyV2Properties,
            BotConfigRepository botConfigRepository
    ) {
        this.environment = environment;
        this.tradingProperties = tradingProperties;
        this.executorProperties = executorProperties;
        this.executorCapabilityService = executorCapabilityService;
        this.liveArmService = liveArmService;
        this.orderLayerProperties = orderLayerProperties;
        this.strategyProperties = strategyProperties;
        this.strategyV2Properties = strategyV2Properties;
        this.botConfigRepository = botConfigRepository;
    }

    @GetMapping("/status")
    public RuntimeStatusResponse status() {
        return new RuntimeStatusResponse(
                Arrays.asList(environment.getActiveProfiles()),
                maskDatasourceUrl(environment.getProperty("spring.datasource.url")),
                tradingProperties.getMode().name(),
                tradingProperties.isKillSwitchEnabled(),
                tradingProperties.isLiveEnabled(),
                liveArmService.status(),
                tradingProperties.getMaxOrderUsd(),
                tradingProperties.getMaxTradesPerMarket(),
                new PortfolioExposurePolicyStatus(
                        tradingProperties.isOnePositionPerBotMarket(),
                        tradingProperties.isOnePositionPerToken(),
                        tradingProperties.getMaxActivePositionsPerMarket(),
                        tradingProperties.getMaxActivePositionsPerPortfolio(),
                        tradingProperties.getLiveRetryCooldownSeconds()
                ),
                tradingProperties.getMaxOpenLiveTrades(),
                tradingProperties.getAllowedStrategyIds(),
                new ExecutorStatus(
                        executorProperties.isEnabled(),
                        executorProperties.isDryRun(),
                        executorProperties.getBaseUrl(),
                        executorProperties.isRequireImmediateFill(),
                        executorCapabilityService.report()
                ),
                new OrderLayerStatus(
                        orderLayerProperties.isEnabled(),
                        orderLayerProperties.getReconciliation() != null && orderLayerProperties.getReconciliation().isEnabled()
                ),
                strategyProperties.activeOrDefault(),
                strategyV2Properties.getEngine().getActiveStrategyIds(),
                botConfigRepository.findAllByEnabledTrueOrderByIdAsc().stream()
                        .map(EnabledBotStatus::from)
                        .toList()
        );
    }

    private String maskDatasourceUrl(String value) {
        if (value == null) {
            return null;
        }
        return PASSWORD_PATTERN.matcher(value).replaceAll("$1****");
    }

    public record RuntimeStatusResponse(
            List<String> activeProfiles,
            String datasourceUrl,
            String tradingMode,
            boolean killSwitchEnabled,
            boolean liveEnabled,
            LiveArmService.LiveArmStatus liveArm,
            BigDecimal maxOrderUsd,
            int maxTradesPerMarket,
            PortfolioExposurePolicyStatus portfolioExposurePolicy,
            int maxOpenLiveTrades,
            Set<String> allowedStrategyIds,
            ExecutorStatus executor,
            OrderLayerStatus orderLayer,
            String currentTopLevelActiveStrategy,
            List<String> strategyV2ActiveInnerStrategyIds,
            List<EnabledBotStatus> enabledBots
    ) {
    }

    public record PortfolioExposurePolicyStatus(
            boolean onePositionPerBotMarket,
            boolean onePositionPerToken,
            int maxActivePositionsPerMarket,
            int maxActivePositionsPerPortfolio,
            long liveEntryAttemptCooldownSeconds
    ) {
    }

    public record ExecutorStatus(
            boolean enabled,
            boolean dryRun,
            String baseUrl,
            boolean requireImmediateFill,
            ExecutorCapabilityService.ExecutorCapabilityReport capabilities
    ) {
    }

    public record OrderLayerStatus(
            boolean enabled,
            boolean reconciliationEnabled
    ) {
    }

    public record EnabledBotStatus(
            Long id,
            String name,
            String strategyId,
            String strategySetId,
            String subStrategyId,
            String marketFamily,
            String status,
            boolean enabled
    ) {
        static EnabledBotStatus from(BotConfigEntity entity) {
            return new EnabledBotStatus(
                    entity.getId(),
                    entity.getName(),
                    entity.getStrategyId(),
                    entity.getStrategySetId(),
                    entity.getSubStrategyId(),
                    entity.getMarketFamily() == null ? null : entity.getMarketFamily().name(),
                    entity.getStatus() == null ? null : entity.getStatus().name(),
                    entity.isEnabled()
            );
        }
    }
}
