package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.outbox.AcceptedOrderIntent;
import com.vokerg.voktrader.trade.outbox.TransactionalOrderIntentService;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Transactional acceptance boundary for live order work.
 *
 * <p>This service persists the immutable intent and dispatch row before the
 * executor worker can observe the request. It deliberately has no executor
 * client dependency.</p>
 */
@Service
@RequiredArgsConstructor
public class DurableOrderAcceptanceService {
    private final TransactionalOrderIntentService intentService;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final LiveArmService liveArmService;
    private final ExecutorProperties executorProperties;

    @Transactional
    public OrderLifecycleResult accept(TradeIntent intent, ExecutionMode mode, String riskDecisionId) {
        if (intent.side() == TradeSide.SELL) {
            return acceptExit(intent, mode, riskDecisionId);
        }
        return acceptEntry(intent, mode, riskDecisionId);
    }

    private OrderLifecycleResult acceptEntry(TradeIntent intent, ExecutionMode mode, String riskDecisionId) {
        if (mode == ExecutionMode.LIVE) {
            LiveArmService.LiveArmStatus armStatus = liveArmService.status();
            if (!armStatus.entryAllowed()) {
                String message = "LIVE entry rejected before durable acceptance: " + armStatus.entryBlockReason();
                return rejected(null, message);
            }
        }
        if (intent.orderType().canRestOnBook() && executorProperties.isRequireImmediateFill()) {
            String message = "LIVE maker entry rejected before durable acceptance: orderType="
                    + intent.orderType()
                    + " can rest on the book while voktrader.executor.require-immediate-fill=true";
            return rejected(null, message);
        }

        AcceptedOrderIntent accepted = intentService.accept(intent, mode, riskDecisionId);
        Optional<TradeOrderEntity> replay = tradeOrderRepository.findByClientOrderId(accepted.clientOrderId());
        if (replay.isPresent()) {
            return current(replay.orElseThrow(), "durable order intent replayed");
        }

        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, accepted.clientOrderId()
        ));
        trade.markEntryPending();
        tradeRepository.save(trade);
        return OrderLifecycleResult.of(trade, order, true, "accepted for durable dispatch");
    }

    private OrderLifecycleResult acceptExit(TradeIntent intent, ExecutionMode mode, String riskDecisionId) {
        TradeEntity trade = findActivePositionTrade(intent).orElse(null);
        if (trade == null) {
            return rejected(null, "sell rejected: no open or partially open trade to close");
        }

        TradePositionSupport.ExitPlan exitPlan = TradePositionSupport.planExit(trade, intent.shares());
        if (!exitPlan.hasRequestedShares()) {
            return rejected(trade, "sell rejected: active trade has no held shares to close");
        }
        TradeIntent sellIntent = TradePositionSupport.withSellShares(intent, exitPlan.requestedShares());
        if (sellIntent.orderType().canRestOnBook() && executorProperties.isRequireImmediateFill()) {
            return rejected(trade, "LIVE maker exit rejected before durable acceptance: orderType="
                    + sellIntent.orderType()
                    + " can rest on the book while voktrader.executor.require-immediate-fill=true");
        }

        AcceptedOrderIntent accepted = intentService.accept(sellIntent, mode, riskDecisionId);
        Optional<TradeOrderEntity> replay = tradeOrderRepository.findByClientOrderId(accepted.clientOrderId());
        if (replay.isPresent()) {
            return current(replay.orElseThrow(), "durable order intent replayed");
        }

        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), sellIntent, mode, TradeVenue.POLYMARKET, accepted.clientOrderId()
        ));
        trade.markExitPending();
        tradeRepository.save(trade);
        return OrderLifecycleResult.of(trade, order, true, "accepted for durable dispatch");
    }

    private Optional<TradeEntity> findActivePositionTrade(TradeIntent intent) {
        return intent.botId() == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                        intent.strategyId(), intent.marketId(), intent.tokenId(), TradePositionSupport.EXITABLE_STATUSES
                )
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                        intent.botId(), intent.strategyId(), intent.marketId(), intent.tokenId(),
                        TradePositionSupport.EXITABLE_STATUSES
                );
    }

    private OrderLifecycleResult current(TradeOrderEntity order, String message) {
        TradeEntity trade = order.getTradeId() == null
                ? null
                : tradeRepository.findById(order.getTradeId()).orElse(null);
        return OrderLifecycleResult.of(trade, order, true, message);
    }

    private OrderLifecycleResult rejected(TradeEntity trade, String message) {
        return new OrderLifecycleResult(
                false,
                trade == null ? null : trade.getId(),
                null,
                null,
                null,
                trade == null ? null : trade.getStatus(),
                null,
                message,
                message
        );
    }
}
