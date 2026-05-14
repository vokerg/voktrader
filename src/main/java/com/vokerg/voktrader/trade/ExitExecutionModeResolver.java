package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ExitExecutionModeResolver {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;

    public ExitExecutionContext resolve(TradeIntent intent, ExecutionMode configuredMode) {
        if (intent == null || intent.side() != TradeSide.SELL) {
            return ExitExecutionContext.configured(configuredMode);
        }
        Optional<TradeEntity> openTrade = findOpenTrade(intent);
        if (openTrade.isEmpty() || !isLiveBackedTrade(openTrade.get())) {
            return ExitExecutionContext.configured(configuredMode);
        }
        return new ExitExecutionContext(liveModeFor(openTrade.get()), true, openTrade.get());
    }

    private Optional<TradeEntity> findOpenTrade(TradeIntent intent) {
        if (intent.botId() != null) {
            return tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                    intent.botId(),
                    intent.strategyId(),
                    intent.marketId(),
                    intent.tokenId(),
                    TradeStatus.OPEN
            );
        }
        return tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                intent.strategyId(),
                intent.marketId(),
                intent.tokenId(),
                TradeStatus.OPEN
        );
    }

    private boolean isLiveBackedTrade(TradeEntity trade) {
        if (isLiveMode(trade.getMode())) {
            return true;
        }
        return entryOrders(trade).stream()
                .anyMatch(order -> isLiveMode(order.getMode())
                        || order.getVenue() == TradeVenue.POLYMARKET
                        || hasText(order.getRemoteOrderId()));
    }

    private ExecutionMode liveModeFor(TradeEntity trade) {
        if (isLiveMode(trade.getMode())) {
            return trade.getMode();
        }
        return entryOrders(trade).stream()
                .map(TradeOrderEntity::getMode)
                .filter(this::isLiveMode)
                .findFirst()
                .orElse(ExecutionMode.LIVE_TINY);
    }

    private List<TradeOrderEntity> entryOrders(TradeEntity trade) {
        if (trade.getId() == null) {
            return List.of();
        }
        return tradeOrderRepository.findByTradeId(trade.getId()).stream()
                .filter(order -> order.getPhase() == TradeOrderPhase.ENTRY)
                .toList();
    }

    private boolean isLiveMode(ExecutionMode mode) {
        return mode == ExecutionMode.LIVE_TINY || mode == ExecutionMode.LIVE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ExitExecutionContext(
            ExecutionMode mode,
            boolean liveBacked,
            TradeEntity openTrade
    ) {
        static ExitExecutionContext configured(ExecutionMode mode) {
            return new ExitExecutionContext(mode, false, null);
        }
    }
}
