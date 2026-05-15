package com.vokerg.voktrader.api.dashboard;

import com.vokerg.voktrader.api.dashboard.dto.DashboardOptionsResponse;
import com.vokerg.voktrader.bot.BotStatus;
import com.vokerg.voktrader.bot.MarketFamily;
import com.vokerg.voktrader.market.MarketResolutionStatus;
import com.vokerg.voktrader.market.MarketTrackingStatus;
import com.vokerg.voktrader.strategy.StrategyRegistry;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeStatus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardOptionsController {
    private final StrategyRegistry strategyRegistry;

    public DashboardOptionsController(StrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    @GetMapping("/options")
    public DashboardOptionsResponse options() {
        return new DashboardOptionsResponse(
                Arrays.stream(MarketFamily.values())
                        .map(family -> new DashboardOptionsResponse.MarketFamilyOption(
                                family.name(),
                                family.asset().name(),
                                family.intervalCode()
                        ))
                        .toList(),
                strategyRegistry.strategyDescriptions().entrySet().stream()
                        .map(entry -> toStrategyOption(entry.getKey(), entry.getValue()))
                        .toList(),
                Arrays.stream(BotStatus.values()).map(Enum::name).toList(),
                Arrays.stream(TradeStatus.values()).map(Enum::name).toList(),
                Arrays.stream(ExecutionMode.values()).map(Enum::name).toList(),
                Arrays.stream(MarketTrackingStatus.values()).map(Enum::name).toList(),
                Arrays.stream(MarketResolutionStatus.values()).map(Enum::name).toList()
        );
    }

    private DashboardOptionsResponse.StrategyOption toStrategyOption(String id, TradingStrategy.StrategyDescription description) {
        return new DashboardOptionsResponse.StrategyOption(
                id,
                description.name(),
                description.status(),
                description.intent()
        );
    }
}
