package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.marketdata.TickRounding;
import com.vokerg.voktrader.marketdata.TickSizeService;
import com.vokerg.voktrader.trade.EntryAcceptanceService;
import com.vokerg.voktrader.trade.EntryIntent;
import com.vokerg.voktrader.trade.ExitIntent;
import com.vokerg.voktrader.trade.ExitSubmissionService;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Component
public class StrategyV2OrderActionBuilder {
    private static final int SCALE = 8;

    private final EntryAcceptanceService entryAcceptanceService;
    private final ExitSubmissionService exitSubmissionService;
    private final TradingProperties tradingProperties;
    private final TickSizeService tickSizeService;

    public StrategyV2OrderActionBuilder(
            EntryAcceptanceService entryAcceptanceService,
            ExitSubmissionService exitSubmissionService,
            TradingProperties tradingProperties,
            TickSizeService tickSizeService
    ) {
        this.entryAcceptanceService = entryAcceptanceService;
        this.exitSubmissionService = exitSubmissionService;
        this.tradingProperties = tradingProperties;
        this.tickSizeService = tickSizeService;
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
        BigDecimal price;
        try {
            price = price(action, context);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    "Strategy V2 tick validation failed: " + e.getMessage());
        }
        BigDecimal shares = shares(action, orderType, postOnly);
        BigDecimal amountUsd = amountUsd(action, price, shares);
        if (amountUsd == null && "fixed_shares".equalsIgnoreCase(action.getSize().getType())) {
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    fixedSharesRejectionMessage(action, price, shares));
        }
        OutcomePrice outcomePrice = new OutcomePrice(
                context.candidate().tokenId(),
                context.candidate().outcome(),
                context.decimal("candidate.bid"),
                context.decimal("candidate.ask"),
                context.candidate().spread(),
                context.now()
        );
        EntryIntent intent = EntryIntent.buy(
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
        ).withRestingTtlSeconds(restingTtlSeconds(action, orderType));
        return entryAcceptanceService.accept(intent);
    }

    public TradeExecutionResult routeExit(
            StrategyV2Properties.Strategy strategy,
            StrategyV2Properties.ExitRule rule,
            StrategyV2FeatureContext context
    ) {
        ExecutionMode mode = configuredMode();
        BigDecimal shares = context.runtimeState() == null ? null : context.runtimeState().filledShares();
        if (shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    "Strategy V2 exit requires positive held shares");
        }
        TradeOrderType orderType = orderType(rule == null ? null : rule.getOrderType());
        String liquidityRole = rule == null ? null : rule.getLiquidityRole();
        boolean postOnly = liquidityRole != null
                ? "maker".equalsIgnoreCase(liquidityRole)
                : orderType.prefersMaker();
        BigDecimal price;
        try {
            price = exitPrice(context, orderType, postOnly);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    "Strategy V2 exit tick validation failed: " + e.getMessage());
        }
        OutcomePrice outcomePrice = new OutcomePrice(
                context.candidate().tokenId(),
                context.candidate().outcome(),
                context.decimal("candidate.bid"),
                context.decimal("candidate.ask"),
                context.candidate().spread(),
                context.now()
        );
        ExitIntent intent = ExitIntent.sell(
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
        return exitSubmissionService.submit(intent);
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
            BigDecimal value = shares.multiply(price).setScale(SCALE, RoundingMode.HALF_UP);
            if (size.getMaxUsd() != null && value.compareTo(size.getMaxUsd()) > 0) {
                return null;
            }
            if (size.getMinUsd() != null && value.compareTo(size.getMinUsd()) < 0) {
                return null;
            }
            return value;
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

    private Integer restingTtlSeconds(StrategyV2Properties.Action action, TradeOrderType orderType) {
        if (action == null || orderType != TradeOrderType.GTD) {
            return null;
        }
        StrategyV2Properties.MakerLifecycle lifecycle = action.getMakerLifecycle();
        if (lifecycle == null || lifecycle.getCancelAfterSeconds() <= 0) {
            return null;
        }
        return lifecycle.getCancelAfterSeconds();
    }

    private String fixedSharesRejectionMessage(StrategyV2Properties.Action action, BigDecimal price, BigDecimal shares) {
        if (shares == null || price == null) {
            return "Strategy V2 fixed_shares entry requires both shares and price";
        }
        StrategyV2Properties.Size size = action.getSize();
        BigDecimal notional = shares.multiply(price).setScale(SCALE, RoundingMode.HALF_UP);
        if (size.getMaxUsd() != null && notional.compareTo(size.getMaxUsd()) > 0) {
            return "Strategy V2 fixed_shares entry exceeds size.max-usd: notional=" + notional + " maxUsd=" + size.getMaxUsd();
        }
        if (size.getMinUsd() != null && notional.compareTo(size.getMinUsd()) < 0) {
            return "Strategy V2 fixed_shares entry is below size.min-usd: notional=" + notional + " minUsd=" + size.getMinUsd();
        }
        return "Strategy V2 fixed_shares entry is invalid";
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
        String tokenId = context.candidate().tokenId();
        BigDecimal tick = tickSizeService.requireTickSize(tokenId);
        int ticks = config.getOffsetTicks() + config.getImproveByTicks();
        if (ticks != 0) {
            BigDecimal offset = tick.multiply(new BigDecimal(ticks));
            price = TradeSide.BUY.name().equalsIgnoreCase(action.getSide()) ? price.add(offset) : price.subtract(offset);
        }
        if (config.getMinPrice() != null) {
            price = price.max(config.getMinPrice());
        }
        if (config.getMaxPrice() != null) {
            price = price.min(config.getMaxPrice());
        }
        String roundingValue = config.getRounding() == null ? "exact" : config.getRounding().toLowerCase(Locale.ROOT);
        TickRounding rounding = switch (roundingValue) {
            case "ceil_to_tick" -> TickRounding.CEILING;
            case "floor_to_tick" -> TickRounding.FLOOR;
            case "nearest_to_tick" -> TickRounding.HALF_UP;
            default -> TickRounding.EXACT;
        };
        return tickSizeService.round(tokenId, price, rounding);
    }

    private TradeOrderType orderType(String value) {
        try {
            return TradeOrderType.valueOf(value == null ? "FOK" : value.toUpperCase(Locale.ROOT));
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
        return price == null ? null : tickSizeService.round(context.candidate().tokenId(), price, TickRounding.EXACT);
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
