package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionSafetyService {
    public static final String PAPER_EXIT_BLOCKED_EVENT = "PAPER_EXIT_BLOCKED_LIVE_TRADE";

    private final TradeOrderRepository tradeOrderRepository;
    private final TradeEventRepository tradeEventRepository;
    private final TradingEventLogger eventLogger;
    private final ObjectMapper objectMapper;

    public Optional<TradeExecutionResult> rejectPaperExitIfLiveBacked(
            TradeEntity trade,
            TradeIntent intent,
            ExecutionMode attemptedMode,
            String callPath
    ) {
        LiveBacking backing = liveBacking(trade);
        if (!backing.liveBacked()) {
            return Optional.empty();
        }
        String message = "paper exit blocked for live-backed trade";
        log.error(
                "{}: tradeId={} tradeMode={} strategy={} marketId={} tokenId={} outcome={} rule={} callPath={} entryVenue={} entryRemoteOrderId={} entryExchangeOrderId={}",
                message,
                trade == null ? null : trade.getId(),
                trade == null ? null : trade.getMode(),
                intent == null ? null : intent.strategyId(),
                intent == null ? null : intent.marketId(),
                intent == null ? null : intent.tokenId(),
                intent == null ? null : intent.outcome(),
                intent == null ? null : intent.ruleId(),
                callPath,
                backing.entryVenue(),
                backing.entryRemoteOrderId(),
                backing.entryExchangeOrderId()
        );
        saveBlockedEvent(trade, intent, attemptedMode, callPath, backing, message);
        return Optional.of(TradeExecutionResult.rejected(
                attemptedMode,
                trade == null ? null : trade.getId(),
                null,
                trade == null ? null : trade.getStatus(),
                null,
                message
        ));
    }

    public void assertNoPaperExitOrderForLiveBackedTrade(TradeOrderEntity order, String callPath) {
        if (order == null || order.getPhase() != TradeOrderPhase.EXIT || order.getVenue() != TradeVenue.PAPER_SIM) {
            return;
        }
        LiveBacking backing = liveBacking(order.getTradeId());
        if (!backing.liveBacked()) {
            return;
        }
        throw new IllegalStateException("PAPER_SIM exit order blocked for live-backed trade"
                + " tradeId=" + order.getTradeId()
                + " orderId=" + order.getId()
                + " callPath=" + callPath
                + " entryVenue=" + backing.entryVenue()
                + " entryRemoteOrderId=" + backing.entryRemoteOrderId()
                + " entryExchangeOrderId=" + backing.entryExchangeOrderId());
    }

    public void assertPaperMayCloseTrade(TradeEntity trade, TradeIntent intent, String callPath) {
        LiveBacking backing = liveBacking(trade);
        if (!backing.liveBacked()) {
            return;
        }
        throw new IllegalStateException("paper close blocked for live-backed trade"
                + " tradeId=" + (trade == null ? null : trade.getId())
                + " rule=" + (intent == null ? null : intent.ruleId())
                + " callPath=" + callPath
                + " entryVenue=" + backing.entryVenue()
                + " entryRemoteOrderId=" + backing.entryRemoteOrderId()
                + " entryExchangeOrderId=" + backing.entryExchangeOrderId());
    }

    public boolean isLiveBackedTrade(TradeEntity trade) {
        return liveBacking(trade).liveBacked();
    }

    public LiveBacking liveBacking(TradeEntity trade) {
        if (trade == null) {
            return LiveBacking.notLive();
        }
        LiveBacking orderBacking = liveBacking(trade.getId());
        if (orderBacking.liveBacked()) {
            return orderBacking;
        }
        if (isLiveMode(trade.getMode())) {
            return new LiveBacking(true, null, null, null, null, "trade mode " + trade.getMode());
        }
        return LiveBacking.notLive();
    }

    public LiveBacking liveBacking(Long tradeId) {
        if (tradeId == null) {
            return LiveBacking.notLive();
        }
        List<TradeOrderEntity> entries = tradeOrderRepository.findByTradeId(tradeId).stream()
                .filter(order -> order.getPhase() == TradeOrderPhase.ENTRY)
                .toList();
        for (TradeOrderEntity order : entries) {
            if (isLiveMode(order.getMode())
                    || order.getVenue() == TradeVenue.POLYMARKET
                    || hasText(order.getRemoteOrderId())
                    || hasText(order.getExchangeOrderId())) {
                return new LiveBacking(
                        true,
                        order.getId(),
                        order.getVenue(),
                        order.getRemoteOrderId(),
                        order.getExchangeOrderId(),
                        "live-backed entry order"
                );
            }
        }
        return LiveBacking.notLive();
    }

    private void saveBlockedEvent(
            TradeEntity trade,
            TradeIntent intent,
            ExecutionMode attemptedMode,
            String callPath,
            LiveBacking backing,
            String message
    ) {
        String payloadJson = null;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tradeId", trade == null ? null : trade.getId());
            payload.put("attemptedMode", attemptedMode);
            payload.put("strategyId", intent == null ? null : intent.strategyId());
            payload.put("ruleId", intent == null ? null : intent.ruleId());
            payload.put("botId", intent == null ? null : intent.botId());
            payload.put("marketId", intent == null ? null : intent.marketId());
            payload.put("tokenId", intent == null ? null : intent.tokenId());
            payload.put("outcome", intent == null ? null : intent.outcome());
            payload.put("callPath", callPath);
            payload.put("entryOrderId", backing.entryOrderId());
            payload.put("entryVenue", backing.entryVenue());
            payload.put("entryRemoteOrderId", backing.entryRemoteOrderId());
            payload.put("entryExchangeOrderId", backing.entryExchangeOrderId());
            payload.put("reason", backing.reason());
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize paper-exit block payload tradeId={}", trade == null ? null : trade.getId(), e);
        }
        tradeEventRepository.save(TradeEventEntity.of(
                trade == null ? null : trade.getId(),
                null,
                null,
                PAPER_EXIT_BLOCKED_EVENT,
                message,
                payloadJson
        ));
        eventLogger.execution(
                PAPER_EXIT_BLOCKED_EVENT,
                "EXECUTION",
                intent == null ? null : intent.strategyId(),
                intent == null ? null : intent.ruleId(),
                intent == null ? null : intent.botId(),
                intent == null ? null : intent.marketId(),
                intent == null ? null : intent.tokenId(),
                intent == null ? null : intent.outcome(),
                message,
                TelemetryData.data(
                        "mode", attemptedMode,
                        "tradeId", trade == null ? null : trade.getId(),
                        "tradeMode", trade == null ? null : trade.getMode(),
                        "callPath", callPath,
                        "entryOrderId", backing.entryOrderId(),
                        "entryVenue", backing.entryVenue(),
                        "entryRemoteOrderId", backing.entryRemoteOrderId(),
                        "entryExchangeOrderId", backing.entryExchangeOrderId()
                ),
                true
        );
    }

    private boolean isLiveMode(ExecutionMode mode) {
        return mode == ExecutionMode.LIVE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record LiveBacking(
            boolean liveBacked,
            Long entryOrderId,
            TradeVenue entryVenue,
            String entryRemoteOrderId,
            String entryExchangeOrderId,
            String reason
    ) {
        static LiveBacking notLive() {
            return new LiveBacking(false, null, null, null, null, null);
        }
    }
}
