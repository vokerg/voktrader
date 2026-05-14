package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRouter {
    private final TradingProperties properties;
    private final PaperExecutionService paperExecutionService;
    private final LiveShadowExecutionService liveShadowExecutionService;
    private final LiveExecutionService liveExecutionService;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;

    public TradeExecutionResult route(TradeIntent intent) {
        TradeIntentExecutor override = ExecutionOverrideContext.current();
        if (override != null) {
            return override.execute(intent);
        }

        ExecutionMode mode = properties.getMode() == null ? ExecutionMode.PAPER : properties.getMode();
        log.debug("Routing trade intent: mode={} strategy={} marketId={} tokenId={} outcome={} side={} amountUsd={} limitPrice={} reason={}",
                mode, intent.strategyId(), intent.marketId(), intent.tokenId(), intent.outcome(), intent.side(), intent.amountUsd(), intent.expectedPrice(), intent.reason());
        if (intent.side() == TradeSide.SELL) {
            Optional<TradeEntity> openTrade = findOpenTrade(intent);
            if (openTrade.isPresent() && isLiveBackedTrade(openTrade.get())) {
                ExecutionMode liveMode = liveModeFor(openTrade.get());
                if (mode != liveMode) {
                    log.error(
                            "Blocking {} exit route for live-backed trade; forcing live executor tradeId={} tradeMode={} liveMode={} strategy={} marketId={} tokenId={} reason={}",
                            mode,
                            openTrade.get().getId(),
                            openTrade.get().getMode(),
                            liveMode,
                            intent.strategyId(),
                            intent.marketId(),
                            intent.tokenId(),
                            intent.reason()
                    );
                }
                return liveExecutionService.execute(intent, liveMode);
            }
        }
        return switch (mode) {
            case PAPER -> paperExecutionService.execute(intent);
            case LIVE_SHADOW -> liveShadowExecutionService.execute(intent);
            case LIVE_TINY, LIVE -> liveExecutionService.execute(intent, mode);
            case TESTING -> TradeExecutionResult.rejected(mode, null, null, null, null, "TESTING mode requires execution override");
        };
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
}
