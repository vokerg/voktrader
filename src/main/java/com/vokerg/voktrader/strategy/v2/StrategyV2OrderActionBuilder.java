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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class StrategyV2OrderActionBuilder {
    private static final int SCALE = 8;
    private final StrategyV2ExecutionProperties executionProperties;
    private final ExecutionRouter executionRouter;
    private final OrderGateway orderGateway;

    public StrategyV2OrderActionBuilder(
            StrategyV2ExecutionProperties executionProperties,
            ExecutionRouter executionRouter,
            OrderGateway orderGateway
    ) {
        this.executionProperties = executionProperties;
        this.executionRouter = executionRouter;
        this.orderGateway = orderGateway;
    }

    public TradeExecutionResult routeEntry(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context,
            ExecutionMode mode
    ) {
        StrategyV2Properties.Action action = strategy.getEntry().getAction();
        if (!"BUY".equalsIgnoreCase(action.getSide())) {
            return TradeExecutionResult.rejected(mode, null, null, null, null, "Strategy V2 entry only supports BUY actions in this version");
        }
        TradeOrderType orderType = orderType(action.getOrderType());
        boolean postOnly = action.getPostOnly() != null
                ? action.getPostOnly()
                : "maker".equalsIgnoreCase(action.getLiquidityRole());
        BigDecimal price = price(action, context);
        BigDecimal amountUsd = amountUsd(action);
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

    private BigDecimal amountUsd(StrategyV2Properties.Action action) {
        StrategyV2Properties.Size size = action.getSize();
        BigDecimal value = size.getPaperUsd() == null ? new BigDecimal("1.00") : size.getPaperUsd();
        if (size.getMaxUsd() != null && value.compareTo(size.getMaxUsd()) > 0) {
            value = size.getMaxUsd();
        }
        if (size.getMinUsd() != null && value.compareTo(size.getMinUsd()) < 0) {
            value = size.getMinUsd();
        }
        return value;
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
