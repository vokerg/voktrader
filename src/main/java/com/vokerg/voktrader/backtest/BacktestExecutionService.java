package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeFillEntity;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderEntity;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.TradeSide;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.TradeVenue;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.time.TimeMachine;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@RequiredArgsConstructor
class BacktestExecutionService {
    private static final int SCALE = 8;

    private final String runId;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final PolymarketFeeCalculator feeCalculator;
    private final TradingProperties tradingProperties;

    TradeExecutionResult execute(TradeIntent intent) {
        return intent.side() == TradeSide.BUY ? buy(intent) : sell(intent);
    }

    String runId() {
        return runId;
    }

    private TradeExecutionResult buy(TradeIntent intent) {
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.TESTING);
        trade.attachBacktestRun(runId);
        trade = tradeRepository.save(trade);

        String clientOrderId = clientOrderId(intent, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(),
                intent,
                ExecutionMode.TESTING,
                TradeVenue.BACKTEST_SIM,
                clientOrderId
        ));
        order.markSubmitting(clientOrderId, intent.toString());

        BigDecimal price = intent.expectedPrice();
        BigDecimal amountUsd = intent.amountUsd();
        BigDecimal shares = intent.shares();
        if (shares == null && amountUsd != null && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            shares = amountUsd.divide(price, SCALE, RoundingMode.HALF_UP);
        }
        if (amountUsd == null && shares != null && price != null) {
            amountUsd = shares.multiply(price).setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (price == null || shares == null || shares.compareTo(BigDecimal.ZERO) <= 0 || amountUsd == null) {
            String message = "backtest buy rejected: missing executable price/size";
            order.markFailed(message);
            trade.markFailed(message);
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            return TradeExecutionResult.rejected(ExecutionMode.TESTING, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        BigDecimal feeUsd = feeCalculator.estimateFeeUsd(shares, price, tradingProperties.getTakerFeeRate());
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.backtest(
                trade.getId(),
                order.getId(),
                TradeSide.BUY,
                price,
                shares,
                amountUsd,
                feeUsd,
                "TAKER",
                "{\"backtest\":true,\"side\":\"BUY\"}"
        ));
        order.markFilled("backtest-" + order.getId(), price, shares, amountUsd);
        trade.markOpen(price, shares, amountUsd, feeUsd, intent.priceUpdatedAt());
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        return TradeExecutionResult.accepted(ExecutionMode.TESTING, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "backtest buy filled");
    }

    private TradeExecutionResult sell(TradeIntent intent) {
        Optional<TradeEntity> open = intent.botId() == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                        intent.strategyId(), intent.marketId(), intent.tokenId(), TradeStatus.OPEN)
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                        intent.botId(), intent.strategyId(), intent.marketId(), intent.tokenId(), TradeStatus.OPEN);
        if (open.isEmpty() || !runId.equals(open.get().getBacktestRunId())) {
            return TradeExecutionResult.rejected(ExecutionMode.TESTING, null, null, null, null, "backtest sell rejected: no open run trade");
        }

        TradeEntity trade = open.get();
        String clientOrderId = clientOrderId(intent, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(),
                intent,
                ExecutionMode.TESTING,
                TradeVenue.BACKTEST_SIM,
                clientOrderId
        ));
        order.markSubmitting(clientOrderId, intent.toString());

        BigDecimal price = intent.expectedPrice();
        BigDecimal shares = intent.shares() == null ? trade.getEntryFilledShares() : intent.shares();
        BigDecimal amountUsd = price == null || shares == null ? null : price.multiply(shares).setScale(SCALE, RoundingMode.HALF_UP);
        if (price == null || shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            String message = "backtest sell rejected: missing executable price/size";
            order.markFailed(message);
            tradeOrderRepository.save(order);
            return TradeExecutionResult.rejected(ExecutionMode.TESTING, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        BigDecimal feeUsd = feeCalculator.estimateFeeUsd(shares, price, tradingProperties.getTakerFeeRate());
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.backtest(
                trade.getId(),
                order.getId(),
                TradeSide.SELL,
                price,
                shares,
                amountUsd,
                feeUsd,
                "TAKER",
                "{\"backtest\":true,\"side\":\"SELL\"}"
        ));
        order.markFilled("backtest-" + order.getId(), price, shares, amountUsd);
        trade.markClosed(price, shares, amountUsd, feeUsd, intent.priceUpdatedAt());
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        return TradeExecutionResult.accepted(ExecutionMode.TESTING, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "backtest sell filled");
    }

    private String clientOrderId(TradeIntent intent, Long tradeId) {
        return "BACKTEST:" + runId + ":" + tradeId + ":" + intent.side() + ":" + intent.tokenId() + ":" + TimeMachine.now().toEpochMilli();
    }
}
