package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.ExitEconomy;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.time.TimeMachine;
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
    private final TradingEventLogger eventLogger;

    public void evaluateCurrentMarketOpenTrades(
            String strategyId,
            String ruleId,
            BigDecimal minimumProfitUsd,
            PriceRecorder priceRecorder,
            ExitEvaluator evaluator
    ) {
        evaluateCurrentMarketOpenTradesWithDecision(
                strategyId,
                ruleId,
                minimumProfitUsd,
                priceRecorder,
                analysis -> evaluator.evaluate(analysis).map(ExitDecision::sellNow)
        );
    }

    public void evaluateCurrentMarketOpenTradesWithDecision(
            String strategyId,
            String ruleId,
            BigDecimal minimumProfitUsd,
            PriceRecorder priceRecorder,
            ExitDecisionEvaluator evaluator
    ) {
        evaluateOpenTradesWithDecision(
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
        evaluateOpenTradesWithDecision(
                strategyId,
                ruleId,
                market,
                minimumProfitUsd,
                priceRecorder,
                analysis -> evaluator.evaluate(analysis).map(ExitDecision::sellNow)
        );
    }

    public void evaluateOpenTradesWithDecision(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            BigDecimal minimumProfitUsd,
            PriceRecorder priceRecorder,
            ExitDecisionEvaluator evaluator
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
                    .ifPresent(decision -> {
                        if (decision.action() == ExitAction.WAIT_FOR_RESOLUTION) {
                            eventLogger.execution(
                                    "EXIT_WAIT_FOR_RESOLUTION",
                                    "EXIT",
                                    strategyId,
                                    ruleId,
                                    trade.getBotId(),
                                    market.id(),
                                    price.tokenId(),
                                    price.outcome(),
                                    decision.reason(),
                                    TelemetryData.data(
                                            "tradeId", trade.getId(),
                                            "entryAvgPrice", trade.getEntryAvgPrice(),
                                            "entryShares", trade.getEntryFilledShares(),
                                            "exitBid", price.bid(),
                                            "estimatedNetPnlUsd", economy.estimatedNetPnlUsd(),
                                            "minimumProfitUsd", economy.minimumProfitUsd(),
                                            "secondsToExpiry", market.endDate() == null ? null : java.time.Duration.between(TimeMachine.now(), market.endDate()).toSeconds(),
                                            "action", decision.action()
                                    ),
                                    true
                            );
                            return;
                        }
                        String reason = decision.reason();
                        eventLogger.exitSignal(
                                strategyId,
                                ruleId,
                                market,
                                price,
                                reason,
                                TelemetryData.data(
                                        "tradeId", trade.getId(),
                                        "entryAvgPrice", trade.getEntryAvgPrice(),
                                        "entryShares", trade.getEntryFilledShares(),
                                        "exitBid", price.bid(),
                                        "estimatedNetPnlUsd", economy.estimatedNetPnlUsd(),
                                        "minimumProfitUsd", economy.minimumProfitUsd(),
                                        "minimumProfitReached", economy.minimumProfitReached(),
                                        "liquidityRole", economy.liquidityRole()
                                )
                        );
                        routeSell(strategyId, ruleId, market, trade, price, reason);
                    });
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
        eventLogger.routed(
                "EXIT",
                strategyId,
                ruleId,
                market,
                price,
                reason,
                TelemetryData.data(
                        "accepted", result.accepted(),
                        "mode", result.mode(),
                        "tradeId", result.tradeId(),
                        "orderId", result.orderId(),
                        "tradeStatus", result.tradeStatus(),
                        "orderStatus", result.orderStatus(),
                        "message", result.message()
                )
        );
    }

    @FunctionalInterface
    public interface ExitEvaluator {
        Optional<String> evaluate(ExitAnalysis analysis);
    }

    @FunctionalInterface
    public interface ExitDecisionEvaluator {
        Optional<ExitDecision> evaluate(ExitAnalysis analysis);
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
        public long secondsToExpiry() {
            if (market == null || market.endDate() == null) {
                return Long.MAX_VALUE;
            }
            return java.time.Duration.between(TimeMachine.now(), market.endDate()).toSeconds();
        }

        public boolean canWaitForResolutionWithin(long seconds) {
            return secondsToExpiry() <= seconds;
        }
    }

    public enum ExitAction {
        SELL_NOW,
        WAIT_FOR_RESOLUTION
    }

    public record ExitDecision(
            ExitAction action,
            String reason
    ) {
        public static ExitDecision sellNow(String reason) {
            return new ExitDecision(ExitAction.SELL_NOW, reason);
        }

        public static ExitDecision waitForResolution(String reason) {
            return new ExitDecision(ExitAction.WAIT_FOR_RESOLUTION, reason);
        }
    }
}
