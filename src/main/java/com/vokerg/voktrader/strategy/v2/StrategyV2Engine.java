package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketDataProvider;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderGatewayContext;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeStateProvider;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.TradingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
public class StrategyV2Engine implements TradingStrategy {
    public static final String ID = "strategy-v2";

    private final StrategyV2Properties properties;
    private final StrategyV2Registry registry;
    private final StrategyMarketDataProvider marketDataProvider;
    private final TrackedMarketState trackedMarketState;
    private final StrategyV2FeatureResolver featureResolver;
    private final StrategyV2EntryEvaluator entryEvaluator;
    private final StrategyV2ExitEvaluator exitEvaluator;
    private final StrategyV2DiagnosticsRecorder diagnosticsRecorder;
    private final StrategyV2ExecutionProperties executionProperties;
    private final TradeStateProvider tradeStateProvider;
    private final OrderGateway orderGateway;
    private final TradingProperties tradingProperties;

    public StrategyV2Engine(
            StrategyV2Properties properties,
            StrategyV2Registry registry,
            StrategyMarketDataProvider marketDataProvider,
            TrackedMarketState trackedMarketState,
            StrategyV2FeatureResolver featureResolver,
            StrategyV2EntryEvaluator entryEvaluator,
            StrategyV2ExitEvaluator exitEvaluator,
            StrategyV2DiagnosticsRecorder diagnosticsRecorder,
            StrategyV2ExecutionProperties executionProperties,
            TradeStateProvider tradeStateProvider,
            OrderGateway orderGateway,
            TradingProperties tradingProperties
    ) {
        this.properties = properties;
        this.registry = registry;
        this.marketDataProvider = marketDataProvider;
        this.trackedMarketState = trackedMarketState;
        this.featureResolver = featureResolver;
        this.entryEvaluator = entryEvaluator;
        this.exitEvaluator = exitEvaluator;
        this.diagnosticsRecorder = diagnosticsRecorder;
        this.executionProperties = executionProperties;
        this.tradeStateProvider = tradeStateProvider;
        this.orderGateway = orderGateway;
        this.tradingProperties = tradingProperties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyDescription description() {
        return new StrategyDescription(
                "Strategy V2 config engine",
                properties.getEngine().isEnabled() ? "enabled" : "disabled",
                "Interprets YAML-backed Strategy V2 configs instead of hardcoded strategy classes.",
                "Uses StrategyMarketView, full book summaries, rolling price samples, and configured feature namespace.",
                "Selects a candidate, evaluates a small condition tree, and routes configured BUY entry actions.",
                "Exit schema is validated; execution remains delegated to existing hardcoded exits until V2 exit execution is enabled.",
                "Good for parameter sweeps and safer config-only experiments.",
                "Maker/live behavior is deliberately blocked by existing lifecycle guardrails when reconciliation is absent.",
                "Put configured strategy ids under strategy-v2.engine.active-strategy-ids or leave empty to run all enabled V2 strategies."
        );
    }

    @Override
    public void tick() {
        if (!properties.getEngine().isEnabled()) {
            return;
        }
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        StrategyMarketView marketView = marketDataProvider.currentUpDownMarket().orElse(null);
        if (market == null || marketView == null) {
            return;
        }
        if (properties.getEngine().isRequireMidSumSane() && !midSumSane(marketView)) {
            return;
        }
        ExecutionMode mode = tradingProperties.getMode() == null ? ExecutionMode.PAPER : tradingProperties.getMode();
        for (StrategyV2Properties.Strategy strategy : registry.activeStrategies()) {
            if (!modeAllowed(strategy, mode)) {
                continue;
            }
            StrategyRuntimeState state = executionProperties.isUseOrderLayer()
                    ? tradeStateProvider.getState(StrategyInstanceKey.of(BotRuntimeContextHolder.currentBotId().orElse(null), strategy.getStrategyId()), market.id())
                    : null;
            boolean accepted = executionProperties.isUseOrderLayer()
                    ? evaluateStateAware(strategy, market, marketView, mode, state)
                    : evaluateEntry(strategy, market, marketView, mode, null);
            if (accepted) {
                break;
            }
        }
    }

    private boolean evaluateEntry(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyMarketView marketView,
            ExecutionMode mode,
            StrategyRuntimeState state
    ) {
        if (state == null) {
            exitEvaluator.evaluate(strategy);
        } else {
            exitEvaluator.evaluate(strategy, state);
        }
        BigDecimal orderUsd = strategy.getEntry().getAction().getSize().getPaperUsd();
        List<StrategyV2FeatureContext> contexts = state == null
                ? featureResolver.contexts(market, marketView, orderUsd)
                : featureResolver.contexts(market, marketView, orderUsd, state);
        return entryEvaluator.evaluate(strategy, contexts, featureResolver, mode)
                    .map(result -> result.accepted() && "single_market_single_position".equals(properties.getEngine().getDecisionMode()))
                    .orElse(false);
    }

    private boolean evaluateStateAware(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyMarketView marketView,
            ExecutionMode mode,
            StrategyRuntimeState state
    ) {
        TradeStatus status = state == null ? TradeStatus.NEW : state.currentTradeStatus();
        if (status == null || status == TradeStatus.NEW || !status.isActive()) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "ENTRY", "no active trade; evaluating entry");
            return evaluateEntry(strategy, market, marketView, mode, state);
        }
        if (status.isPendingEntry()) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "ENTRY_PENDING_MANAGEMENT", "entry order pending; suppressing duplicate entry");
            maybeCancelEntryPending(strategy, state);
            return false;
        }
        if (status == TradeStatus.PARTIALLY_OPEN) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "PARTIAL_POSITION_MANAGEMENT", "partial position active; suppressing duplicate entry");
            maybeCancelPartialRemainder(strategy, state);
            if (strategy.getPartialFillManagement().isAllowExitPartialPosition()) {
                exitEvaluator.evaluate(strategy, state);
            }
            return false;
        }
        if (status == TradeStatus.OPEN) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "EXIT", "position open; evaluating exit rules");
            exitEvaluator.evaluate(strategy, state);
            return false;
        }
        if (status.isPendingExit()) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "EXIT_PENDING_MANAGEMENT", "exit order pending; suppressing duplicate exit");
            maybeCancelExitPending(strategy, state);
            return false;
        }
        diagnosticsRecorder.stateBranch(strategy, state, market.id(), "ENTRY", "terminal or unknown trade state; evaluating entry");
        return evaluateEntry(strategy, market, marketView, mode, state);
    }

    private void maybeCancelEntryPending(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        maybeCancelOrder(state.activeEntryOrder(), strategy.getEntryOrderManagement().getMaxPendingSeconds(), "entry pending too long");
    }

    private void maybeCancelPartialRemainder(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        maybeCancelOrder(state.activeEntryOrder(), strategy.getPartialFillManagement().getCancelRemainingOnPartialAfterSeconds(), "partial entry remainder pending too long");
    }

    private void maybeCancelExitPending(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        maybeCancelOrder(state.activeExitOrder(), strategy.getExitOrderManagement().getMaxPendingSeconds(), "exit pending too long");
    }

    private void maybeCancelOrder(OrderRuntimeState order, int maxPendingSeconds, String reason) {
        if (order == null || maxPendingSeconds <= 0) {
            return;
        }
        Long ageSeconds = order.ageSeconds(TimeMachine.now());
        String cancelIdentifier = order.cancelIdentifier();
        if (ageSeconds == null || ageSeconds <= maxPendingSeconds || cancelIdentifier == null || cancelIdentifier.isBlank()) {
            return;
        }
        OrderLifecycleResult result = OrderGatewayContext.current().orElse(orderGateway).cancelOrder(cancelIdentifier, reason);
        if (!result.success()) {
            log.warn("Strategy V2 order-layer cancel request failed for {}: {}", cancelIdentifier, result.message());
        } else {
            log.info("Strategy V2 requested cancel for {} because {}", cancelIdentifier, reason);
        }
    }

    private boolean modeAllowed(StrategyV2Properties.Strategy strategy, ExecutionMode mode) {
        List<String> allowed = strategy.getAllowedExecutionModes();
        return allowed == null || allowed.isEmpty() || allowed.stream().anyMatch(value -> value.equalsIgnoreCase(mode.name()));
    }

    private boolean midSumSane(StrategyMarketView marketView) {
        BigDecimal up = marketView.outcome("Up").map(com.vokerg.voktrader.strategy.StrategyOutcomeView::mid).orElse(null);
        BigDecimal down = marketView.outcome("Down").map(com.vokerg.voktrader.strategy.StrategyOutcomeView::mid).orElse(null);
        if (up == null || down == null) {
            return !properties.getEngine().isRequireCompleteUpDownPrice();
        }
        BigDecimal sum = up.add(down);
        return sum.compareTo(properties.getEngine().getMinMidSum()) >= 0
                && sum.compareTo(properties.getEngine().getMaxMidSum()) <= 0;
    }

}
