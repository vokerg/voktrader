package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.RiskSeverity;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeRiskCheckEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RiskCheckService {
    private static final List<TradeStatus> ACTIVE_STATUSES = List.of(
            TradeStatus.CREATED,
            TradeStatus.ENTRY_PENDING,
            TradeStatus.PARTIALLY_OPEN,
            TradeStatus.OPEN,
            TradeStatus.EXIT_PENDING,
            TradeStatus.PARTIALLY_CLOSED
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
    private final LiveArmService liveArmService;

    /** Sole policy evaluation method for a new-position entry. */
    public RiskAssessment assessEntry(EntryRiskRequest request) {
        TradeIntent intent = request.tradeIntent();
        ExecutionMode mode = request.mode();
        String correlationId = request.correlationId();
        RiskAssessment assessment = new RiskAssessment(correlationId);

        boolean strategyAllowed = properties.getAllowedStrategyIds().isEmpty()
                || properties.getAllowedStrategyIds().contains(intent.strategyId());
        assessment.add(check(correlationId, mode, "STRATEGY_WHITELIST", strategyAllowed,
                intent.strategyId(), properties.getAllowedStrategyIds(), RiskSeverity.BLOCK,
                strategyAllowed ? "strategy allowed" : "strategy is not whitelisted"));

        boolean amountOk = intent.amountUsd() != null
                && intent.amountUsd().compareTo(BigDecimal.ZERO) > 0
                && intent.amountUsd().compareTo(properties.getMaxOrderUsd()) <= 0;
        assessment.add(check(correlationId, mode, "MAX_ORDER_USD", amountOk,
                intent.amountUsd(), properties.getMaxOrderUsd(), RiskSeverity.BLOCK,
                amountOk ? "order size accepted" : "order size is missing, non-positive, or above maxOrderUsd"));

        boolean spreadOk = intent.observedSpread() != null
                && intent.observedSpread().compareTo(properties.getMaxSpread()) <= 0;
        assessment.add(check(correlationId, mode, "MAX_SPREAD", spreadOk,
                intent.observedSpread(), properties.getMaxSpread(), RiskSeverity.BLOCK,
                spreadOk ? "spread accepted" : "spread is missing or too wide"));

        boolean priceFresh = intent.priceAgeMs() != null && intent.priceAgeMs() <= properties.getMaxPriceAgeMs();
        assessment.add(check(correlationId, mode, "PRICE_FRESHNESS", priceFresh,
                intent.priceAgeMs(), properties.getMaxPriceAgeMs(), RiskSeverity.BLOCK,
                priceFresh ? "price is fresh" : "price is stale or timestamp is missing"));

        if (intent.secondsToExpiryAtDecision() == null) {
            assessment.add(check(correlationId, mode, "EXPIRY_GUARD", false,
                    null, properties.getMinSecondsToExpiry(), RiskSeverity.WARN,
                    "market expiry is unavailable; expiry guard could not be evaluated"));
        } else {
            boolean expiryOk = intent.secondsToExpiryAtDecision() >= properties.getMinSecondsToExpiry();
            assessment.add(check(correlationId, mode, "EXPIRY_GUARD", expiryOk,
                    intent.secondsToExpiryAtDecision(), properties.getMinSecondsToExpiry(), RiskSeverity.BLOCK,
                    expiryOk ? "market not too close to expiry" : "market too close to expiry"));
        }

        PortfolioSnapshot portfolio = portfolioSnapshot(intent, mode);
        boolean onePerMarketOk = !properties.isOnePositionPerBotMarket()
                || portfolio.activePositionsInMarket() == 0;
        assessment.add(check(correlationId, mode, "ONE_POSITION_PER_BOT_MARKET", onePerMarketOk,
                portfolio.activePositionsInMarket(), properties.isOnePositionPerBotMarket(), RiskSeverity.BLOCK,
                onePerMarketOk
                        ? "bot has no conflicting active market exposure"
                        : "another inner strategy already reserves or holds exposure for this bot and market"));

        boolean onePerTokenOk = !properties.isOnePositionPerToken()
                || portfolio.activePositionsForToken() == 0;
        assessment.add(check(correlationId, mode, "ONE_POSITION_PER_TOKEN", onePerTokenOk,
                portfolio.activePositionsForToken(), properties.isOnePositionPerToken(), RiskSeverity.BLOCK,
                onePerTokenOk
                        ? "bot has no conflicting active token exposure"
                        : "active exposure already exists for this bot, market, and token"));

        int maxPerMarket = properties.getMaxActivePositionsPerMarket();
        boolean marketCapOk = maxPerMarket <= 0 || portfolio.activePositionsInMarket() < maxPerMarket;
        assessment.add(check(correlationId, mode, "MAX_ACTIVE_POSITIONS_PER_MARKET", marketCapOk,
                portfolio.activePositionsInMarket(), maxPerMarket, RiskSeverity.BLOCK,
                marketCapOk ? "active market exposure accepted" : "maxActivePositionsPerMarket reached"));

        int maxPerPortfolio = properties.getMaxActivePositionsPerPortfolio();
        boolean portfolioCapOk = maxPerPortfolio <= 0 || portfolio.activePositionsInPortfolio() < maxPerPortfolio;
        assessment.add(check(correlationId, mode, "MAX_ACTIVE_POSITIONS_PER_PORTFOLIO", portfolioCapOk,
                portfolio.activePositionsInPortfolio(), maxPerPortfolio, RiskSeverity.BLOCK,
                portfolioCapOk ? "active portfolio exposure accepted" : "maxActivePositionsPerPortfolio reached"));

        boolean correlationOk = tradeOrderRepository.findByClientOrderId(correlationId).isEmpty();
        assessment.add(check(correlationId, mode, "ENTRY_CORRELATION", correlationOk,
                correlationId, "unique pre-order correlation ID", RiskSeverity.BLOCK,
                correlationOk ? "entry correlation accepted" : "entry correlation already belongs to an order"));

        if (mode == ExecutionMode.LIVE) {
            long cooldownSeconds = properties.getLiveRetryCooldownSeconds();
            if (cooldownSeconds > 0) {
                Instant cooldownSince = TimeMachine.now().minus(Duration.ofSeconds(cooldownSeconds));
                boolean hasRecentRejectedEntry = tradeOrderRepository.existsRecentOrder(
                        intent.botId(),
                        intent.strategyId(),
                        intent.marketId(),
                        intent.tokenId(),
                        TradeSide.BUY,
                        TradeOrderPhase.ENTRY,
                        mode,
                        COOLDOWN_STATUSES,
                        cooldownSince
                );
                assessment.add(check(correlationId, mode, "LIVE_ENTRY_ATTEMPT_COOLDOWN", !hasRecentRejectedEntry,
                        hasRecentRejectedEntry ? "recent rejected entry attempt" : "no recent rejected entry attempt",
                        cooldownSeconds + "s", RiskSeverity.BLOCK,
                        hasRecentRejectedEntry ? "recent live entry attempt was rejected" : "live entry attempt cooldown accepted"));
            }

            boolean killSwitchOk = !properties.isKillSwitchEnabled();
            assessment.add(check(correlationId, mode, "KILL_SWITCH", killSwitchOk,
                    properties.isKillSwitchEnabled(), false, RiskSeverity.BLOCK,
                    killSwitchOk ? "kill switch disabled" : "kill switch is enabled"));

            boolean liveEnabledOk = properties.isLiveEnabled();
            assessment.add(check(correlationId, mode, "LIVE_ENABLED", liveEnabledOk,
                    properties.isLiveEnabled(), true, RiskSeverity.BLOCK,
                    liveEnabledOk ? "live trading explicitly enabled" : "live trading not explicitly enabled"));

            LiveArmService.LiveArmStatus armStatus = liveArmService.status();
            assessment.add(check(correlationId, mode, "LIVE_ARM", armStatus.entryAllowed(),
                    armStatus.armed(), true, RiskSeverity.BLOCK,
                    armStatus.entryAllowed() ? "live arm active" : armStatus.entryBlockReason()));

            long openLiveTrades = tradeRepository.countLiveCapacityTrades(
                    List.of(ExecutionMode.LIVE), ACTIVE_STATUSES, TimeMachine.now());
            boolean openLiveOk = openLiveTrades < properties.getMaxOpenLiveTrades();
            assessment.add(check(correlationId, mode, "MAX_OPEN_LIVE_TRADES", openLiveOk,
                    openLiveTrades, properties.getMaxOpenLiveTrades(), RiskSeverity.BLOCK,
                    openLiveOk ? "open live trade count accepted" : "maxOpenLiveTrades reached"));
        }

        return assessment;
    }

    /**
     * Compatibility assertion used by the existing execution adapters.
     * It no longer evaluates policy. A BUY reaches those adapters only while a
     * matching, already-persisted central decision is present.
     */
    @Deprecated
    public RiskAssessment assess(TradeIntent intent, ExecutionMode mode, Long tradeId, Long orderId, String idempotencyKey) {
        if (intent.side() != TradeSide.BUY) {
            return new RiskAssessment(idempotencyKey);
        }
        return EntryRiskDecisionContext.current()
                .filter(decision -> decision.request().matches(intent, mode))
                .map(decision -> new RiskAssessment(decision.request().correlationId()))
                .orElseGet(() -> missingBoundaryAssessment(mode, idempotencyKey));
    }

    private RiskAssessment missingBoundaryAssessment(ExecutionMode mode, String correlationId) {
        RiskAssessment assessment = new RiskAssessment(correlationId);
        assessment.add(check(correlationId, mode, "ENTRY_RISK_BOUNDARY_REQUIRED", false,
                "no approved entry context", "approved central entry risk decision", RiskSeverity.BLOCK,
                "BUY rejected because it did not cross EntryAcceptanceService"));
        return assessment;
    }

    private PortfolioSnapshot portfolioSnapshot(TradeIntent intent, ExecutionMode mode) {
        List<TradeEntity> exposure = tradeRepository.findPortfolioExposure(intent.botId(), mode, ACTIVE_STATUSES);
        PortfolioSnapshot.PortfolioKey key = new PortfolioSnapshot.PortfolioKey(
                intent.botId(),
                accountNamespace(mode),
                intent.marketId(),
                intent.tokenId(),
                intent.outcome(),
                mode
        );
        return PortfolioSnapshot.from(key, exposure);
    }

    private String accountNamespace(ExecutionMode mode) {
        if (mode == ExecutionMode.LIVE) {
            String expectedAccountId = properties.getExpectedAccountId();
            return expectedAccountId == null || expectedAccountId.isBlank()
                    ? "live-account-unconfigured"
                    : expectedAccountId.trim();
        }
        return mode.name().toLowerCase(Locale.ROOT) + "-account";
    }

    private TradeRiskCheckEntity check(
            String correlationId,
            ExecutionMode mode,
            String name,
            boolean passed,
            Object observed,
            Object limit,
            RiskSeverity failureSeverity,
            String message
    ) {
        return TradeRiskCheckEntity.of(
                null,
                null,
                correlationId,
                mode,
                name,
                passed,
                passed ? RiskSeverity.INFO : failureSeverity,
                observed,
                limit,
                message
        );
    }
}
