package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyExitSupport {
    private final LatestPriceState latestPriceState;
    private final StrategyTradeSupport tradeSupport;
    private final ExecutionRouter executionRouter;

    public void evaluateOpenTrades(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
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

            evaluator.evaluate(new OpenTradeContext(market, trade, price))
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
        Optional<String> evaluate(OpenTradeContext context);
    }

    public record OpenTradeContext(
            GammaMarketDto market,
            TradeEntity trade,
            OutcomePrice price
    ) {
    }
}
