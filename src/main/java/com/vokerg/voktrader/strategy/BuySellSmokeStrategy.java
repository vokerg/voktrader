package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeStatus;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated
public class BuySellSmokeStrategy implements TradingStrategy {

    public static final String ID = "buy-sell-smoke";

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final ExecutionRouter executionRouter;
    private final TradeRepository tradeRepository;
    private final StrategyProperties strategyProperties;
    private final StrategyTimeWindow strategyTimeWindow;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyDescription description() {
        return new StrategyDescription(
                "Buy/sell smoke",
                "Deprecated scaffold. Useful for exercising buy and sell plumbing, not recommended for strategy research.",
                "Buys a configurable outcome, or any outcome, when ask is either very low or very high and spread is tight. Sells an open trade when simple gross PnL exceeds a threshold.",
                "Uses latest top-of-book prices, current market, trade repository state, and simple gross PnL. It does not use StrategyMarketView, order book depth, fee-aware economy, or advanced trade guards.",
                "Finds the cheapest matching candidate whose ask crosses configured low/high thresholds and whose spread is tight. It prevents one open trade per bot/market, but does not use cooldowns or loss lockouts.",
                "Sells when bid multiplied by shares minus entry filled USD reaches configured minimum profit. This ignores fee drag and does not estimate executable depth.",
                "Good for confirming the system can open and close trades through the router. Slightly more complete than the one-way simple strategy because it has an exit path.",
                "Weak for actual trading because the buy rule is arbitrary and can select extreme prices without context. High ask can mean chasing; low ask can mean buying a dying side. "
                        + "Exit PnL ignores fees, slippage, stale prices, and book size. It can look profitable in logs while a real taker exit would not fill cleanly.",
                "Do not adapt this into a production strategy. Use it only as operational smoke coverage, then prefer StrategyMarketView-based strategies for new work."
        );
    }

    @Override
    public void tick() {
        if (!strategyTimeWindow.isInsideTradingWindow()) {
            return;
        }

        trySellOpenSignals();
        tryBuySignal();
    }

    private void trySellOpenSignals() {
        var market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        var config = strategyProperties.buySellSmokeOrDefault();
        Long botId = currentBotId();

        for (TradeEntity trade : tradeRepository.findByStrategyIdAndStatus(ID, TradeStatus.OPEN)) {
            if (!sameBotScope(trade, botId)) {
                continue;
            }
            if (!market.id().equals(trade.getMarketId())) {
                continue;
            }

            OutcomePrice price = latestPriceState
                    .byTokenId(trade.getTokenId())
                    .orElse(null);

            if (price == null || price.bid() == null) {
                continue;
            }

            BigDecimal exitValueUsd = trade.getEntryFilledShares().multiply(price.bid());
            BigDecimal grossPnl = exitValueUsd.subtract(trade.getEntryFilledUsd());

            if (grossPnl.compareTo(config.minProfitUsdOrDefault()) < 0) {
                continue;
            }

            TradeExecutionResult result = executionRouter.route(TradeIntent.sell(
                    market,
                    price,
                    trade.getEntryFilledShares(),
                    ID,
                    "buy-sell-smoke",
                    "bid produced gross pnl >= " + config.minProfitUsdOrDefault()
            ));
            log.info(
                    "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                    result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
        }
    }

    private void tryBuySignal() {
        var market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        Long botId = currentBotId();

        if (findOpenTrade(botId, market.id()).isPresent()) {
            return;
        }

        OutcomePrice candidate = findBuyCandidate().orElse(null);

        if (candidate == null) {
            return;
        }

        var config = strategyProperties.buySellSmokeOrDefault();

        TradeExecutionResult result = executionRouter.route(TradeIntent.buy(
                market,
                candidate,
                config.orderSizeUsdOrDefault(),
                ID,
                "buy-sell-smoke",
                "ask <= " + config.buyBelowAskOrDefault()
                        + " or ask >= " + config.buyAboveAskOrDefault()
                        + ", spread <= " + config.maxSpreadOrDefault()
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
    }

    private Optional<OutcomePrice> findBuyCandidate() {
        var config = strategyProperties.buySellSmokeOrDefault();
        String configuredOutcome = config.buyOutcomeOrDefault();

        return latestPriceState
                .allByTokenId()
                .values()
                .stream()
                .filter(price -> outcomeMatches(price, configuredOutcome))
                .filter(this::hasUsableBuyPrice)
                .filter(price -> matchesBuyThreshold(price, config))
                .filter(price -> hasTightSpread(price, config))
                .min(Comparator.comparing(OutcomePrice::ask));
    }

    private boolean outcomeMatches(OutcomePrice price, String configuredOutcome) {
        return "ANY".equalsIgnoreCase(configuredOutcome)
                || price.outcome().equalsIgnoreCase(configuredOutcome);
    }

    private boolean hasUsableBuyPrice(OutcomePrice price) {
        return price.ask() != null
                && price.ask().compareTo(BigDecimal.ZERO) > 0
                && price.spread() != null;
    }

    private boolean matchesBuyThreshold(
            OutcomePrice price,
            StrategyProperties.BuySellSmoke config
    ) {
        boolean below = price.ask().compareTo(config.buyBelowAskOrDefault()) <= 0;
        boolean above = price.ask().compareTo(config.buyAboveAskOrDefault()) >= 0;

        return below || above;
    }

    private boolean hasTightSpread(
            OutcomePrice price,
            StrategyProperties.BuySellSmoke config
    ) {
        return price.spread().compareTo(config.maxSpreadOrDefault()) <= 0;
    }

    private Optional<TradeEntity> findOpenTrade(Long botId, String marketId) {
        return botId == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(ID, marketId, TradeStatus.OPEN)
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(botId, ID, marketId, TradeStatus.OPEN);
    }

    private Long currentBotId() {
        return BotRuntimeContextHolder.currentBotId().orElse(null);
    }

    private boolean sameBotScope(TradeEntity trade, Long botId) {
        return trade.getBotId() == null ? botId == null : trade.getBotId().equals(botId);
    }
}
