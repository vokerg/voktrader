package com.vokerg.voktrader.telemetry;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.backtest.BacktestDiagnosticsContext;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.time.TimeMachine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TradingEventLogger {
    private static final Logger events = LoggerFactory.getLogger("voktrader.events");

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock = Clock.systemUTC();

    public void entryRejected(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data
    ) {
        emit("ENTRY_REJECTED", "ENTRY", strategyId, ruleId, market, price, reason, data, false);
    }

    public void entrySignal(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data
    ) {
        emit("ENTRY_SIGNAL", "ENTRY", strategyId, ruleId, market, price, reason, data, true);
    }

    public void exitSignal(
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data
    ) {
        emit("EXIT_SIGNAL", "EXIT", strategyId, ruleId, market, price, reason, data, true);
    }

    public void routed(
            String phase,
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data
    ) {
        emit("TRADE_ROUTED", phase, strategyId, ruleId, market, price, reason, data, true);
    }

    public void market(
            String type,
            Long botId,
            GammaMarketDto market,
            String reason,
            Map<String, Object> data,
            boolean important
    ) {
        emit(type, "MARKET", null, null, botId, market, null, reason, data, important);
    }

    public void price(
            String type,
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data,
            boolean important
    ) {
        emit(type, "PRICE", null, null, botId, market, price, reason, data, important);
    }

    public void execution(
            String type,
            String phase,
            String strategyId,
            String ruleId,
            Long botId,
            String marketId,
            String tokenId,
            String outcome,
            String reason,
            Map<String, Object> data,
            boolean important
    ) {
        TradingEvent event = new TradingEvent(
                TimeMachine.now(clock),
                type,
                phase,
                strategyId,
                ruleId,
                botId,
                marketId,
                null,
                tokenId,
                outcome,
                reason,
                data == null ? Map.of() : data
        );
        eventPublisher.publishEvent(event);
        BacktestDiagnosticsContext.record(event);
        logJson(event, important);
    }

    private void emit(
            String type,
            String phase,
            String strategyId,
            String ruleId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data,
            boolean important
    ) {
        emit(
                type,
                phase,
                strategyId,
                ruleId,
                BotRuntimeContextHolder.currentBotId().orElse(null),
                market,
                price,
                reason,
                data,
                important
        );
    }

    private void emit(
            String type,
            String phase,
            String strategyId,
            String ruleId,
            Long botId,
            GammaMarketDto market,
            OutcomePrice price,
            String reason,
            Map<String, Object> data,
            boolean important
    ) {
        TradingEvent event = new TradingEvent(
                TimeMachine.now(clock),
                type,
                phase,
                strategyId,
                ruleId,
                botId,
                market == null ? null : market.id(),
                market == null ? null : market.slug(),
                price == null ? null : price.tokenId(),
                price == null ? null : price.outcome(),
                reason,
                data == null ? Map.of() : data
        );
        eventPublisher.publishEvent(event);
        BacktestDiagnosticsContext.record(event);
        logJson(event, important);
    }

    private void logJson(TradingEvent event, boolean important) {
        try {
            String json = objectMapper.writeValueAsString(event);
            if (important) {
                events.info(json);
            } else {
                events.debug(json);
            }
        } catch (JacksonException e) {
            events.warn("Could not serialize trading event type={} reason={}", event.type(), event.reason(), e);
        }
    }
}
