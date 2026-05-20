package com.vokerg.voktrader.trade.paper;

import com.vokerg.voktrader.bot.BotRuntime;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.bot.BotRuntimeManager;
import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaperOrderAdvancementWorker {
    private final TradingProperties tradingProperties;
    private final StrategyV2ExecutionProperties executionProperties;
    private final BotRuntimeManager botRuntimeManager;
    private final PaperOrderGateway paperOrderGateway;

    @Scheduled(
            fixedDelayString = "${voktrader.order-layer.reconciliation.interval-ms:2000}",
            initialDelayString = "${voktrader.order-layer.reconciliation.interval-ms:2000}"
    )
    public void advanceOpenOrders() {
        if (tradingProperties.getMode() != ExecutionMode.PAPER || !executionProperties.isUseOrderLayer()) {
            return;
        }
        for (BotRuntime runtime : botRuntimeManager.runtimes()) {
            try {
                BotRuntimeContextHolder.runWith(runtime.context(), paperOrderGateway::advanceOpenOrders);
            } catch (RuntimeException ex) {
                log.error("Paper order advancement failed botId={} strategyId={}",
                        runtime.botId(),
                        runtime.config().getStrategyId(),
                        ex);
            }
        }
    }
}
