package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketDataProvider;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderGatewayContext;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeStateProvider;
import com.vokerg.voktrader.trade.TradeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final StrategyV2SetCatalog configCatalog;
    private final Map<CooldownKey, Instant> noFillCancelCooldownUntil = new ConcurrentHashMap<>();

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
            StrategyV2SetCatalog configCatalog
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
        this.configCatalog = configCatalog;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyDescription description() {
        StrategyV2Properties effective = effectiveProperties();
        return new StrategyDescription(
                "Strategy V2 config engine",
                effective.getEngine().isEnabled() ? "enabled" : "disabled",
                "Interprets YAML-backed Strategy V2 configs instead of hardcoded strategy classes.",
                "Uses StrategyMarketView, full book summaries, rolling price samples, and configured feature namespace.",
                "Selects a candidate, evaluates a small condition tree, and routes configured BUY entry actions.",
                "Evaluates configured exit rules against runtime position state and routes SELL exits through the configured execution path.",
                "Good for parameter sweeps and safer config-only experiments.",
                "For paper runs it can use DB runtime state while keeping order-layer routing disabled.",
                "Put configured strategy ids under strategy-v2.engine.active-strategy-ids or leave empty to run all enabled V2 strategies."
        );
    }

    @Override
    public void tick() {
        if (StrategyV2OverrideContext.current().isPresent()) {
            tickWithEffectiveConfig();
            return;
        }
        configCatalog.propertiesFor(BotRuntimeContextHolder.currentStrategySetId().orElse(null))
                .ifPresentOrElse(
                        selected -> StrategyV2OverrideContext.runWith(selected, this::tickWithEffectiveConfig),
                        this::tickWithEffectiveConfig
                );
    }

    private void tickWithEffectiveConfig() {
        StrategyV2Properties effective = effectiveProperties();
        if (!effective.getEngine().isEnabled()) {
            return;
        }
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        StrategyMarketView marketView = marketDataProvider.currentUpDownMarket().orElse(null);
        if (market == null || marketView == null) {
            return;
        }
        if (effective.getEngine().isRequireMidSumSane() && !midSumSane(marketView)) {
            return;
        }
        for (StrategyV2Properties.Strategy strategy : strategiesForCurrentBot()) {
            StrategyRuntimeState state = tradeStateProvider.getState(
                    StrategyInstanceKey.of(BotRuntimeContextHolder.currentBotId().orElse(null), strategy.getStrategyId()),
                    market.id()
            );
            boolean accepted = evaluateStateAware(strategy, market, marketView, state);
            if (accepted) {
                break;
            }
        }
    }

    private boolean evaluateEntry(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyMarketView marketView,
            StrategyRuntimeState state
    ) {
        if (state == null) {
            exitEvaluator.evaluate(strategy);
        } else {
            exitEvaluator.evaluate(strategy, market, marketView, state);
        }
        BigDecimal orderUsd = strategy.getEntry().getAction().getSize().getPaperUsd();
        List<StrategyV2FeatureContext> contexts = state == null
                ? featureResolver.contexts(market, marketView, orderUsd)
                : featureResolver.contexts(market, marketView, orderUsd, state);
        contexts = contexts.stream()
                .filter(context -> !entryNoFillCancelCooldownActive(strategy, market, context))
                .toList();
        return entryEvaluator.evaluate(strategy, contexts, featureResolver)
                    .map(result -> result.accepted() && "single_market_single_position".equals(effectiveProperties().getEngine().getDecisionMode()))
                    .orElse(false);
    }

    private boolean evaluateStateAware(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyMarketView marketView,
            StrategyRuntimeState state
    ) {
        TradeStatus status = state == null ? TradeStatus.NEW : state.currentTradeStatus();
        if (status == null || status == TradeStatus.NEW || !status.isActive()) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "ENTRY", "no active trade; evaluating entry");
            return evaluateEntry(strategy, market, marketView, state);
        }
        if (status.isPendingEntry()) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "ENTRY_PENDING_MANAGEMENT", "entry order pending; suppressing duplicate entry");
            maybeCancelEntryPending(strategy, marketView, state);
            return false;
        }
        if (status == TradeStatus.PARTIALLY_OPEN) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "PARTIAL_POSITION_MANAGEMENT", "partial position active; suppressing duplicate entry");
            maybeCancelPartialRemainder(strategy, state);
            if (strategy.getPartialFillManagement().isAllowExitPartialPosition()) {
                exitEvaluator.evaluate(strategy, market, marketView, state);
            }
            return false;
        }
        if (status == TradeStatus.OPEN) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "EXIT", "position open; evaluating exit rules");
            exitEvaluator.evaluate(strategy, market, marketView, state);
            return false;
        }
        if (status.isPendingExit()) {
            diagnosticsRecorder.stateBranch(strategy, state, market.id(), "EXIT_PENDING_MANAGEMENT", "exit order pending; suppressing duplicate exit");
            maybeCancelExitPending(strategy, state);
            return false;
        }
        diagnosticsRecorder.stateBranch(strategy, state, market.id(), "ENTRY", "terminal or unknown trade state; evaluating entry");
        return evaluateEntry(strategy, market, marketView, state);
    }

    private void maybeCancelEntryPending(
            StrategyV2Properties.Strategy strategy,
            StrategyMarketView marketView,
            StrategyRuntimeState state
    ) {
        OrderRuntimeState order = state.activeEntryOrder();
        String reason = entryPendingCancelReason(strategy, marketView, state);
        if (reason == null) {
            return;
        }
        OrderLifecycleResult result = maybeCancelOrder(strategy, state, order, 0, reason, "BEST_BID_MOVED", true);
        if (result != null && result.success() && noFillMakerEntryOrder(strategy, state)) {
            registerNoFillCancelCooldown(strategy, state);
        }
    }

    private void maybeCancelPartialRemainder(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        maybeCancelOrder(strategy, state, state.activeEntryOrder(), strategy.getPartialFillManagement().getCancelRemainingOnPartialAfterSeconds(), "partial entry remainder pending too long", "TIMEOUT");
    }

    private void maybeCancelExitPending(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        maybeCancelOrder(strategy, state, state.activeExitOrder(), strategy.getExitOrderManagement().getMaxPendingSeconds(), "exit pending too long", "TIMEOUT");
    }

    private OrderLifecycleResult maybeCancelOrder(
            StrategyV2Properties.Strategy strategy,
            StrategyRuntimeState state,
            OrderRuntimeState order,
            int maxPendingSeconds,
            String reason,
            String reasonCategory
    ) {
        return maybeCancelOrder(strategy, state, order, maxPendingSeconds, reason, reasonCategory, false);
    }

    private OrderLifecycleResult maybeCancelOrder(
            StrategyV2Properties.Strategy strategy,
            StrategyRuntimeState state,
            OrderRuntimeState order,
            int maxPendingSeconds,
            String reason,
            String reasonCategory,
            boolean force
    ) {
        if (order == null) {
            return null;
        }
        if (maxPendingSeconds <= 0) {
            if (!force) {
                return null;
            }
        }
        Long ageSeconds = order.ageSeconds(TimeMachine.now());
        String cancelIdentifier = order.cancelIdentifier();
        if ((!force && (ageSeconds == null || ageSeconds <= maxPendingSeconds)) || cancelIdentifier == null || cancelIdentifier.isBlank()) {
            return null;
        }
        diagnosticsRecorder.orderCancelLifecycle(
                strategy,
                state,
                order,
                "ELIGIBLE",
                reasonCategory,
                reason,
                ageSeconds,
                maxPendingSeconds,
                null
        );
        OrderLifecycleResult result = OrderGatewayContext.current().orElse(orderGateway).cancelOrder(cancelIdentifier, reason);
        diagnosticsRecorder.orderCancelLifecycle(
                strategy,
                state,
                order,
                "REQUEST_SENT",
                reasonCategory,
                reason,
                ageSeconds,
                maxPendingSeconds,
                result
        );
        if (!result.success()) {
            log.warn("Strategy V2 order-layer cancel request failed for {}: {}", cancelIdentifier, result.message());
        } else {
            log.info("Strategy V2 requested cancel for {} because {}", cancelIdentifier, reason);
        }
        return result;
    }

    private String entryPendingCancelReason(
            StrategyV2Properties.Strategy strategy,
            StrategyMarketView marketView,
            StrategyRuntimeState state
    ) {
        OrderRuntimeState order = state == null ? null : state.activeEntryOrder();
        if (order == null) {
            return null;
        }
        String bestBidReason = bestBidMovedReason(strategy, marketView, state, order);
        if (bestBidReason != null) {
            diagnosticsRecorder.stateBranch(strategy, state, state.marketId(), "MAKER_LIFECYCLE_CANCEL", bestBidReason);
            log.info("Strategy V2 maker lifecycle cancel reason=BEST_BID_MOVED {}", bestBidReason);
            return bestBidReason;
        }
        int timeoutSeconds = entryPendingTimeoutSeconds(strategy);
        Long ageSeconds = order.ageSeconds(TimeMachine.now());
        if (ageSeconds != null && timeoutSeconds > 0 && ageSeconds > timeoutSeconds) {
            String reason = "maker lifecycle timeout; entry pending too long ageSeconds=%s maxPendingSeconds=%s".formatted(ageSeconds, timeoutSeconds);
            diagnosticsRecorder.stateBranch(strategy, state, state.marketId(), "MAKER_LIFECYCLE_CANCEL", reason);
            log.info("Strategy V2 maker lifecycle cancel reason=TIMEOUT {}", reason);
            return reason;
        }
        return null;
    }

    private String bestBidMovedReason(
            StrategyV2Properties.Strategy strategy,
            StrategyMarketView marketView,
            StrategyRuntimeState state,
            OrderRuntimeState order
    ) {
        if (!noFillMakerEntryOrder(strategy, state)
                || marketView == null
                || !strategy.getEntryOrderManagement().isCancelIfPriceMovesAway()) {
            return null;
        }
        StrategyV2Properties.Action action = strategy.getEntry() == null ? null : strategy.getEntry().getAction();
        StrategyV2Properties.MakerLifecycle lifecycle = action == null ? null : action.getMakerLifecycle();
        int moveTicks = lifecycle == null ? 0 : lifecycle.getReplaceIfBestBidMovesTicks();
        if (moveTicks <= 0 || order.requestedPrice() == null || state.tokenId() == null) {
            return null;
        }
        var viewOptional = marketView.token(state.tokenId());
        StrategyOutcomeView view = viewOptional == null ? null : viewOptional.orElse(null);
        var bestBidLevel = view == null ? null : view.bestBidLevel();
        BigDecimal bestBid = bestBidLevel == null || bestBidLevel.isEmpty() ? null : bestBidLevel.get().price();
        BigDecimal tickSize = action.getPrice() == null || action.getPrice().getTickSize() == null
                ? new BigDecimal("0.01")
                : action.getPrice().getTickSize();
        BigDecimal threshold = tickSize.multiply(BigDecimal.valueOf(moveTicks));
        if (bestBid == null || bestBid.subtract(order.requestedPrice()).compareTo(threshold) < 0) {
            return null;
        }
        return "maker best bid moved away; orderPrice=%s bestBid=%s thresholdTicks=%s tickSize=%s"
                .formatted(order.requestedPrice(), bestBid, moveTicks, tickSize);
    }

    private int entryPendingTimeoutSeconds(StrategyV2Properties.Strategy strategy) {
        if (isMakerEntry(strategy)) {
            StrategyV2Properties.Action action = strategy.getEntry() == null ? null : strategy.getEntry().getAction();
            StrategyV2Properties.MakerLifecycle lifecycle = action == null ? null : action.getMakerLifecycle();
            if (lifecycle != null && lifecycle.getCancelAfterSeconds() > 0) {
                return lifecycle.getCancelAfterSeconds();
            }
        }
        return strategy.getEntryOrderManagement().getMaxPendingSeconds();
    }

    private boolean entryNoFillCancelCooldownActive(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyV2FeatureContext context
    ) {
        if (strategy == null || market == null || context == null || context.candidate() == null) {
            return false;
        }
        CooldownKey key = cooldownKey(strategy, market.id(), context.candidate().tokenId(), entrySide(strategy));
        Instant until = noFillCancelCooldownUntil.get(key);
        if (until == null) {
            return false;
        }
        Instant now = TimeMachine.now();
        if (!now.isBefore(until)) {
            noFillCancelCooldownUntil.remove(key, until);
            return false;
        }
        diagnosticsRecorder.rejected(
                strategy,
                context,
                "entry suppressed by maker no-fill cancel cooldown",
                Map.of("cooldownUntil", until, "tokenId", context.candidate().tokenId())
        );
        return true;
    }

    private boolean noFillMakerEntryOrder(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        OrderRuntimeState order = state == null ? null : state.activeEntryOrder();
        if (strategy == null || order == null || order.phase() != com.vokerg.voktrader.trade.TradeOrderPhase.ENTRY) {
            return false;
        }
        BigDecimal filledShares = order.filledShares();
        if (filledShares != null && filledShares.compareTo(BigDecimal.ZERO) > 0) {
            return false;
        }
        StrategyV2Properties.Action action = strategy.getEntry() == null ? null : strategy.getEntry().getAction();
        if (action == null) {
            return false;
        }
        return isMakerEntry(strategy);
    }

    private boolean isMakerEntry(StrategyV2Properties.Strategy strategy) {
        StrategyV2Properties.Action action = strategy == null || strategy.getEntry() == null ? null : strategy.getEntry().getAction();
        return action != null && ("maker".equalsIgnoreCase(action.getLiquidityRole()) || Boolean.TRUE.equals(action.getPostOnly()));
    }

    private void registerNoFillCancelCooldown(StrategyV2Properties.Strategy strategy, StrategyRuntimeState state) {
        StrategyV2Properties.Action action = strategy.getEntry() == null ? null : strategy.getEntry().getAction();
        StrategyV2Properties.MakerLifecycle lifecycle = action == null ? null : action.getMakerLifecycle();
        int cooldownSeconds = lifecycle == null ? 0 : lifecycle.getCooldownAfterNoFillCancelSeconds();
        if (cooldownSeconds <= 0 || state == null || state.tokenId() == null) {
            return;
        }
        CooldownKey key = cooldownKey(strategy, state.marketId(), state.tokenId(), entrySide(strategy));
        Instant until = TimeMachine.now().plusSeconds(cooldownSeconds);
        noFillCancelCooldownUntil.put(key, until);
        log.info("Strategy V2 maker no-fill cancel cooldown set key={} until={}", key, until);
    }

    private CooldownKey cooldownKey(StrategyV2Properties.Strategy strategy, String marketId, String tokenId, String side) {
        return new CooldownKey(
                BotRuntimeContextHolder.currentBotId().orElse(null),
                strategy == null ? null : strategy.getStrategyId(),
                marketId,
                tokenId,
                side == null ? null : side.toUpperCase(Locale.ROOT)
        );
    }

    private String entrySide(StrategyV2Properties.Strategy strategy) {
        StrategyV2Properties.Action action = strategy == null || strategy.getEntry() == null ? null : strategy.getEntry().getAction();
        return action == null || action.getSide() == null ? "BUY" : action.getSide();
    }

    private List<StrategyV2Properties.Strategy> strategiesForCurrentBot() {
        String subStrategyId = BotRuntimeContextHolder.currentSubStrategyId().orElse(null);
        List<StrategyV2Properties.Strategy> active = registry.activeStrategies();
        if (subStrategyId == null || subStrategyId.isBlank()) {
            return active;
        }
        String normalized = subStrategyId.trim().toLowerCase(Locale.ROOT);
        return active.stream()
                .filter(strategy -> strategy.getStrategyId() != null
                        && strategy.getStrategyId().toLowerCase(Locale.ROOT).equals(normalized))
                .toList();
    }

    private boolean midSumSane(StrategyMarketView marketView) {
        BigDecimal up = marketView.outcome("Up").map(com.vokerg.voktrader.strategy.StrategyOutcomeView::mid).orElse(null);
        BigDecimal down = marketView.outcome("Down").map(com.vokerg.voktrader.strategy.StrategyOutcomeView::mid).orElse(null);
        if (up == null || down == null) {
            return !effectiveProperties().getEngine().isRequireCompleteUpDownPrice();
        }
        BigDecimal sum = up.add(down);
        return sum.compareTo(effectiveProperties().getEngine().getMinMidSum()) >= 0
                && sum.compareTo(effectiveProperties().getEngine().getMaxMidSum()) <= 0;
    }

    private StrategyV2Properties effectiveProperties() {
        return StrategyV2OverrideContext.current().orElse(properties);
    }

    private record CooldownKey(Long botId, String strategyId, String marketId, String tokenId, String side) {
    }

}
