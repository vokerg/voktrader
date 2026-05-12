package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RiskCheckService {
    private static final List<TradeStatus> ACTIVE_STATUSES = List.of(
            TradeStatus.CREATED,
            TradeStatus.ENTRY_PENDING,
            TradeStatus.OPEN,
            TradeStatus.EXIT_PENDING
    );
    private static final List<TradeOrderStatus> COOLDOWN_STATUSES = List.of(
            TradeOrderStatus.FAILED,
            TradeOrderStatus.REJECTED,
            TradeOrderStatus.CANCELLED,
            TradeOrderStatus.EXPIRED,
            TradeOrderStatus.TIMEOUT
    );

    private final TradingProperties properties;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;

    public RiskAssessment assess(TradeIntent intent, ExecutionMode mode, Long tradeId, Long orderId, String idempotencyKey) {
        RiskAssessment assessment = new RiskAssessment();

        boolean strategyAllowed = properties.getAllowedStrategyIds().isEmpty()
                || properties.getAllowedStrategyIds().contains(intent.strategyId());
        assessment.add(check(tradeId, orderId, mode, "STRATEGY_WHITELIST", strategyAllowed,
                intent.strategyId(), properties.getAllowedStrategyIds(),
                strategyAllowed ? "strategy allowed" : "strategy is not whitelisted"));

        boolean amountOk = intent.amountUsd() != null
                && intent.amountUsd().compareTo(BigDecimal.ZERO) > 0
                && intent.amountUsd().compareTo(properties.getMaxOrderUsd()) <= 0;
        assessment.add(check(tradeId, orderId, mode, "MAX_ORDER_USD", amountOk,
                intent.amountUsd(), properties.getMaxOrderUsd(),
                amountOk ? "order size accepted" : "order size is missing, non-positive, or above maxOrderUsd"));

        boolean spreadOk = intent.observedSpread() != null
                && intent.observedSpread().compareTo(properties.getMaxSpread()) <= 0;
        assessment.add(check(tradeId, orderId, mode, "MAX_SPREAD", spreadOk,
                intent.observedSpread(), properties.getMaxSpread(),
                spreadOk ? "spread accepted" : "spread is missing or too wide"));

        boolean priceFresh = intent.priceAgeMs() != null && intent.priceAgeMs() <= properties.getMaxPriceAgeMs();
        assessment.add(check(tradeId, orderId, mode, "PRICE_FRESHNESS", priceFresh,
                intent.priceAgeMs(), properties.getMaxPriceAgeMs(),
                priceFresh ? "price is fresh" : "price is stale or timestamp is missing"));

        boolean expiryOk = intent.secondsToExpiryAtDecision() == null
                || intent.secondsToExpiryAtDecision() >= properties.getMinSecondsToExpiry();
        assessment.add(check(tradeId, orderId, mode, "EXPIRY_GUARD", expiryOk,
                intent.secondsToExpiryAtDecision(), properties.getMinSecondsToExpiry(),
                expiryOk ? "market not too close to expiry" : "market too close to expiry"));

        long activeTradeCount = countActiveForToken(intent);
        boolean duplicateIntent = activeTradeCount > 0;
        assessment.add(check(tradeId, orderId, mode, "DUPLICATE_OPEN_TRADE", !duplicateIntent,
                activeTradeCount, "0 active trades before execution",
                duplicateIntent ? "another active trade already exists for this market/token/strategy" : "no active duplicate trade"));

        long activeTradesForMarketIncludingCurrent = countActiveForMarket(intent);
        boolean maxTradesOk = activeTradesForMarketIncludingCurrent < properties.getMaxTradesPerMarket();
        assessment.add(check(tradeId, orderId, mode, "MAX_TRADES_PER_MARKET", maxTradesOk,
                activeTradesForMarketIncludingCurrent, properties.getMaxTradesPerMarket(),
                maxTradesOk ? "market trade count accepted" : "maxTradesPerMarket reached"));

        boolean idempotencyOk = idempotencyKey != null && !idempotencyKey.isBlank()
                && tradeOrderRepository.findByClientOrderId(idempotencyKey)
                .map(existing -> existing.getId().equals(orderId))
                .orElse(true);
        assessment.add(check(tradeId, orderId, mode, "IDEMPOTENCY", idempotencyOk,
                idempotencyKey, "unique non-blank idempotency key",
                idempotencyOk ? "idempotency key accepted" : "duplicate idempotency key belongs to a different order"));

        if (mode == ExecutionMode.LIVE_TINY || mode == ExecutionMode.LIVE) {
            long cooldownSeconds = properties.getLiveRetryCooldownSeconds();
            if (cooldownSeconds > 0 && intent.side() == TradeSide.BUY) {
                Instant cooldownSince = Instant.now().minus(Duration.ofSeconds(cooldownSeconds));
                boolean hasRecentRejectedEntry = tradeOrderRepository.existsRecentOrder(
                        intent.botId(),
                        intent.strategyId(),
                        intent.marketId(),
                        intent.tokenId(),
                        intent.side(),
                        TradeOrderPhase.ENTRY,
                        mode,
                        COOLDOWN_STATUSES,
                        cooldownSince
                );
                assessment.add(check(tradeId, orderId, mode, "LIVE_RETRY_COOLDOWN", !hasRecentRejectedEntry,
                        hasRecentRejectedEntry ? "recent rejected entry" : "no recent rejected entry",
                        cooldownSeconds + "s",
                        hasRecentRejectedEntry ? "recent live entry attempt was rejected" : "live retry cooldown accepted"));
            }

            boolean killSwitchOk = !properties.isKillSwitchEnabled();
            assessment.add(check(tradeId, orderId, mode, "KILL_SWITCH", killSwitchOk,
                    properties.isKillSwitchEnabled(), false,
                    killSwitchOk ? "kill switch disabled" : "kill switch is enabled"));

            boolean liveEnabledOk = properties.isLiveEnabled();
            assessment.add(check(tradeId, orderId, mode, "LIVE_ENABLED", liveEnabledOk,
                    properties.isLiveEnabled(), true,
                    liveEnabledOk ? "live trading explicitly enabled" : "live trading not explicitly enabled"));

            long openLiveTradesIncludingCurrent = tradeRepository.countByModeInAndStatusIn(
                    List.of(ExecutionMode.LIVE_TINY, ExecutionMode.LIVE), ACTIVE_STATUSES);
            long openLiveTrades = Math.max(0, openLiveTradesIncludingCurrent - 1);
            boolean openLiveOk = openLiveTrades < properties.getMaxOpenLiveTrades();
            assessment.add(check(tradeId, orderId, mode, "MAX_OPEN_LIVE_TRADES", openLiveOk,
                    openLiveTrades, properties.getMaxOpenLiveTrades(),
                    openLiveOk ? "open live trade count accepted" : "maxOpenLiveTrades reached"));
        }

        return assessment;
    }

    private long countActiveForToken(TradeIntent intent) { if (intent.botId() != null) { return tradeRepository.countByBotIdAndMarketIdAndTokenIdAndStrategyIdAndStatusIn(intent.botId(), intent.marketId(), intent.tokenId(), intent.strategyId(), ACTIVE_STATUSES); } return tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(intent.marketId(), intent.tokenId(), intent.strategyId(), ACTIVE_STATUSES); } private long countActiveForMarket(TradeIntent intent) { if (intent.botId() != null) { return tradeRepository.countByBotIdAndMarketIdAndStrategyIdAndStatusIn(intent.botId(), intent.marketId(), intent.strategyId(), ACTIVE_STATUSES); } return tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(intent.marketId(), intent.strategyId(), ACTIVE_STATUSES); } private TradeRiskCheckEntity check(Long tradeId, Long orderId, ExecutionMode mode, String name, boolean passed, Object observed, Object limit, String message) {
        return TradeRiskCheckEntity.of(
                tradeId,
                orderId,
                mode,
                name,
                passed,
                passed ? RiskSeverity.INFO : RiskSeverity.BLOCK,
                observed,
                limit,
                message
        );
    }
}
