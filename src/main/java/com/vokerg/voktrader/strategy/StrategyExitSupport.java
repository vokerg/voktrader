package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.ExitEconomy;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyExitSupport {
    private static final BigDecimal TWO = new BigDecimal("2");

    private final TrackedMarketState trackedMarketState;
    private final LatestPriceState latestPriceState;
    private final StrategyTradeSupport tradeSupport;
    private final ExecutionRouter executionRouter;
    private final TradeEconomy tradeEconomy;

    public void evaluateCurrentMarketOpenTrades(
            String strategyId,
            String ruleId,
            BigDecimal minimumProfitUsd,
            PriceRecorder priceRecorder,
            ExitEvaluator evaluator
    ) {
        evaluateOpenTrades(
                strategyId,
                ruleId,
                trackedMarketState.currentMarket().orElse(null),
                minimumProfitUsd,
                priceRecorder,
                evaluator
        );
    }

    public void evaluateOpenTrades(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            BigDecimal minimumProfitUsd,
            PriceRecorder priceRecorder,
            ExitEvaluator evaluator
    ) {
        if (market == null || market.id() == null) {
            return;
        }

        Long botId = tradeSupport.currentBotId();
        for (TradeEntity trade : tradeSupport.openTrades(strategyId, botId, market.id())) {
            OutcomePrice price = latestPriceState.byTokenId(trade.getTokenId()).orElse(null);

            if (!hasCompletePrice(price)) {
                continue;
            }

            priceRecorder.record(price);
            ExitEconomy economy = tradeEconomy.estimateExit(
                    trade,
                    price.bid(),
                    minimumProfitUsd,
                    LiquidityRole.TAKER
            );

            evaluator.evaluate(new ExitAnalysis(market, trade, price, mid(price), economy))
                    .ifPresent(reason -> routeSell(strategyId, ruleId, market, trade, price, reason));
        }
    }

    public static boolean hasCompletePrice(OutcomePrice price) {
        return price != null
                && price.bid() != null
                && price.ask() != null
                && price.spread() != null
                && price.updatedAt() != null;
    }

    private static BigDecimal mid(OutcomePrice price) {
        return price.bid()
                .add(price.ask())
                .divide(TWO, 8, RoundingMode.HALF_UP);
    }

    private void routeSell(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            TradeEntity trade,
            OutcomePrice price,
            String reason
    ) {
        var result = executionRouter.route(TradeIntent.sell(
                market,
                price,
                trade.getEntryFilledShares(),
                strategyId,
                ruleId,
                reason
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
    }

    @FunctionalInterface
    public interface ExitEvaluator {
        Optional<String> evaluate(ExitAnalysis analysis);
    }

    @FunctionalInterface
    public interface PriceRecorder {
        void record(OutcomePrice price);
    }

    public record ExitAnalysis(
            GammaMarketDto market,
            TradeEntity trade,
            OutcomePrice price,
            BigDecimal mid,
            ExitEconomy economy
    ) {
    }
}
