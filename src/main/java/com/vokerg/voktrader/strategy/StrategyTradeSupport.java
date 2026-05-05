package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeRepository;
import com.vokerg.voktrader.trade.TradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class StrategyTradeSupport {

    private final TradeRepository tradeRepository;

    public Long currentBotId() {
        return BotRuntimeContextHolder.currentBotId().orElse(null);
    }

    public List<TradeEntity> openTrades(String strategyId, Long botId, String marketId) {
        return tradeRepository.findByStrategyIdAndStatus(strategyId, TradeStatus.OPEN)
                .stream()
                .filter(trade -> sameBotScope(trade, botId))
                .filter(trade -> marketId.equals(trade.getMarketId()))
                .toList();
    }

    public boolean hasOpenTrade(String strategyId, Long botId, String marketId) {
        return (botId == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
                strategyId,
                marketId,
                TradeStatus.OPEN
        )
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
                botId,
                strategyId,
                marketId,
                TradeStatus.OPEN
        ))
                .isPresent();
    }

    public boolean marketTradeLimitReached(String strategyId, Long botId, String marketId, int maxTrades) {
        if (maxTrades <= 0) {
            return false;
        }

        long trades = botId == null
                ? tradeRepository.countByStrategyIdAndMarketId(strategyId, marketId)
                : tradeRepository.countByBotIdAndStrategyIdAndMarketId(botId, strategyId, marketId);

        return trades >= maxTrades;
    }

    public boolean closedTradeCooldownActive(
            String strategyId,
            Long botId,
            String marketId,
            long cooldownSeconds,
            Instant now
    ) {
        if (cooldownSeconds <= 0) {
            return false;
        }

        return lastFinishedTrade(strategyId, botId, marketId)
                .map(TradeEntity::getUpdatedAt)
                .filter(updatedAt -> Duration.between(updatedAt, now).compareTo(Duration.ofSeconds(cooldownSeconds)) < 0)
                .isPresent();
    }

    public boolean sameOutcomeLossLockoutActive(String strategyId, Long botId, String marketId, String tokenId) {
        return (botId == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(
                strategyId,
                marketId,
                tokenId,
                List.of(TradeStatus.CLOSED, TradeStatus.RESOLVED)
        )
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(
                botId,
                strategyId,
                marketId,
                tokenId,
                List.of(TradeStatus.CLOSED, TradeStatus.RESOLVED)
        ))
                .map(TradeEntity::getFinalPnlUsd)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) < 0)
                .isPresent();
    }

    private Optional<TradeEntity> lastFinishedTrade(String strategyId, Long botId, String marketId) {
        return botId == null
                ? tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                strategyId,
                marketId,
                List.of(TradeStatus.CLOSED, TradeStatus.RESOLVED, TradeStatus.FAILED)
        )
                : tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                botId,
                strategyId,
                marketId,
                List.of(TradeStatus.CLOSED, TradeStatus.RESOLVED, TradeStatus.FAILED)
        );
    }

    private boolean sameBotScope(TradeEntity trade, Long botId) {
        return trade.getBotId() == null ? botId == null : trade.getBotId().equals(botId);
    }
}
