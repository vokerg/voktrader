package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.OrderGateway;
import com.vokerg.voktrader.trade.OrderGatewayContext;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderType;
import com.vokerg.voktrader.trade.TradeSide;
import com.vokerg.voktrader.trade.TradingProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class StrategyV2OrderActionBuilder {
    private static final int SCALE = 8;
    private final StrategyV2ExecutionProperties executionProperties;
    private final ExecutionRouter executionRouter;
    private final OrderGateway orderGateway;
    private final TradingProperties tradingProperties;

    public StrategyV2OrderActionBuilder(
            StrategyV2ExecutionProperties executionProperties,
            ExecutionRouter executionRouter,
            OrderGateway orderGateway,
            TradingProperties tradingProperties
    ) {
        this.executionProperties = executionProperties;
        this.executionRouter = executionRouter;
        this.orderGateway = orderGateway;
        this.tradingProperties = tradingProperties;
    }

    public TradeExecutionResult routeEntry(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context
    ) {
        StrategyV2Properties.Action action = strategy.getEntry().getAction();
        ExecutionMode mode = configuredMode();
        if (!"BUY".equalsIgnoreCase(action.getSide())) {
            return TradeExecutionResult.rejected(mode, null, null, null, null, "Strategy V2 entry only supports BUY actions in this version");
        }
        TradeOrderType orderType = orderType(action.getOrderType());
        boolean postOnly = action.getPostOnly() != null
                ? action.getPostOnly()
                : "maker".equalsIgnoreCase(action.getLiquidityRole());
        BigDecimal price = price(action, context);
        BigDecimal shares = shares(action, orderType, postOnly);
        BigDecimal amountUsd = amountUsd(action, price, shares);
        OutcomePrice outcomePrice = new OutcomePrice(
                context.candidate().tokenId(),
                context.candidate().outcome(),
                context.decimal("candidate.bid"),
                context.decimal("candidate.ask"),
                context.candidate().spread(),
                context.now()
        );
        TradeIntent intent = TradeIntent.buy(
                BotRuntimeContextHolder.currentBotId().orElse(null),
                context.market(),
                outcomePrice,
                amountUsd,
                shares,
                orderType,
                postOnly,
                price,
                strategy.getStrategyId(),
                strategy.getEntry().getRuleId(),
                reason(strategy, context)
        );
        if (executionProperties.isUseOrderLayer()) {
            OrderGateway gateway = OrderGatewayContext.current().orElse(orderGateway);
            return TradeExecutionResult.fromOrderLifecycle(
                    mode,
                    gateway.submitOrder(intent, StrategyInstanceKey.of(intent.botId(), strategy.getStrategyId()), mode)
            );
        }
        return executionRouter.route(intent);
    }

    public TradeExecutionResult routeExit(
            StrategyV2Properties.Strategy strategy,
            StrategyV2Properties.ExitRule rule,
            StrategyV2FeatureContext context
    ) {
        ExecutionMode mode = configuredMode();
        TradeOrderType orderType = orderType(rule == null ? null : rule.getOrderType());
        String liquidityRole = rule == null ? null : rule.getLiquidityRole();
        boolean postOnly = liquidityRole != null
                ? "maker".equalsIgnoreCase(liquidityRole)
                : orderType.prefersMaker();
        BigDecimal price = exitPrice(context, orderType, postOnly);
        BigDecimal shares = context.runtimeState() == null ? null : context.runtimeState().filledShares();
        OutcomePrice outcomePrice = new OutcomePrice(
                context.candidate().tokenId(),
                context.candidate().outcome(),
                context.decimal("candidate.bid"),
                context.decimal("candidate.ask"),
                context.candidate().spread(),
                context.now()
        );
        TradeIntent intent = TradeIntent.sell(
                BotRuntimeContextHolder.currentBotId().orElse(null),
                context.market(),
                outcomePrice,
                shares,
                orderType,
                postOnly,
                price,
                strategy.getStrategyId(),
                ruleId(strategy, rule),
                exitReason(strategy, rule, context)
        );
        if (executionProperties.isUseOrderLayer()) {
            OrderGateway gateway = OrderGatewayContext.current().orElse(orderGateway);
            return TradeExecutionResult.fromOrderLifecycle(
                    mode,
                    gateway.submitOrder(intent, StrategyInstanceKey.of(intent.botId(), strategy.getStrategyId()), mode)
            );
        }
        return executionRouter.route(intent);
    }

    private ExecutionMode configuredMode() {
        ExecutionMode mode = tradingProperties.getMode();
        if (mode == null) {
            throw new IllegalStateException("voktrader.trading.mode must be configured explicitly");
        }
        return mode;
    }

    private BigDecimal amountUsd(StrategyV2Properties.Action action, BigDecimal price, BigDecimal shares) {
        StrategyV2Properties.Size size = action.getSize();
        if ("fixed_shares".equalsIgnoreCase(size.getType())) {
            if (shares == null || price == null) {
                return null;
            }
            return shares.multiply(price).setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal value = size.getUsd() == null ? new BigDecimal("1.00") : size.getUsd();
        if (size.getMaxUsd() != null && value.compareTo(size.getMaxUsd()) > 0) {
            value = size.getMaxUsd();
        }
        if (size.getMinUsd() != null && value.compareTo(size.getMinUsd()) < 0) {
            value = size.getMinUsd();
        }
        return value;
    }

    private BigDecimal shares(StrategyV2Properties.Action action, TradeOrderType orderType, boolean postOnly) {
        StrategyV2Properties.Size size = action.getSize();
        if (!"fixed_shares".equalsIgnoreCase(size.getType())) {
            return null;
        }
        if (size.getShares() != null) {
            return size.getShares();
        }
        if (postOnly || orderType.canRestOnBook()) {
            return tradingProperties.getMinMakerOrderShares();
        }
        return BigDecimal.ONE;
    }

    private BigDecimal price(StrategyV2Properties.Action action, StrategyV2FeatureContext context) {
        StrategyV2Properties.Price config = action.getPrice();
        BigDecimal price = switch (config.getSource() == null ? "best_ask" : config.getSource()) {
            case "best_bid" -> context.decimal("candidate.bid");
            case "mid" -> context.decimal("candidate.mid");
            case "fixed" -> config.getMinPrice();
            default -> context.decimal("candidate.ask");
        };
        if (price == null) {
            price = context.decimal("candidate.ask");
        }
        BigDecimal tick = config.getTickSize() == null ? new BigDecimal("0.01") : config.getTickSize();
        int ticks = config.getOffsetTicks() + config.getImproveByTicks();
        if (ticks != 0) {
            BigDecimal offset = tick.multiply(new BigDecimal(ticks));
            price = TradeSide.BUY.name().equalsIgnoreCase(action.getSide()) ? price.add(offset) : price.subtract(offset);
        }
        if ("ceil_to_tick".equalsIgnoreCase(config.getRounding())) {
            price = price.divide(tick, 0, RoundingMode.CEILING).multiply(tick);
        } else if ("floor_to_tick".equalsIgnoreCase(config.getRounding())) {
            price = price.divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
        }
        if (config.getMinPrice() != null) {
            price = price.max(config.getMinPrice());
        }
        if (config.getMaxPrice() != null) {
            price = price.min(config.getMaxPrice());
        }
        return price.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private TradeOrderType orderType(String value) {
        try {
            return TradeOrderType.valueOf(value == null ? "FOK" : value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown Strategy V2 order_type: " + value);
        }
    }

    private BigDecimal exitPrice(StrategyV2FeatureContext context, TradeOrderType orderType, boolean postOnly) {
        BigDecimal price = postOnly || orderType.prefersMaker()
                ? context.decimal("candidate.ask")
                : context.decimal("candidate.bid");
        if (price == null) {
            price = context.decimal("candidate.mid");
        }
        return price == null ? null : price.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String ruleId(StrategyV2Properties.Strategy strategy, StrategyV2Properties.ExitRule rule) {
        if (rule != null && rule.getName() != null && !rule.getName().isBlank()) {
            return rule.getName();
        }
        return strategy.getExit() == null ? "exit" : strategy.getExit().getRuleId();
    }

    private String exitReason(StrategyV2Properties.Strategy strategy, StrategyV2Properties.ExitRule rule, StrategyV2FeatureContext context) {
        String name = rule == null || rule.getName() == null ? ruleId(strategy, rule) : rule.getName();
        return "strategy-v2 exit strategy=" + strategy.getStrategyId()
                + " rule=" + name
                + " outcome=" + context.candidate().outcome();
    }

    private String reason(StrategyV2Properties.Strategy strategy, StrategyV2FeatureContext context) {
        String template = strategy.getEntry().getAction().getReasonTemplate();
        if (template == null || template.isBlank()) {
            return "strategy-v2 entry strategy=" + strategy.getStrategyId() + " candidate=" + context.candidate().outcome();
        }
        String reason = template;
        for (var entry : context.features().entrySet()) {
            reason = reason.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return reason;
    }
}
