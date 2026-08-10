package com.vokerg.voktrader.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DurableOrderCancellationWorker {
    private final OrderLayerProperties properties;
    private final DurableOrderCancellationService cancellationService;

    @Scheduled(
            fixedDelayString = "${voktrader.order-layer.reconciliation.interval-ms:2000}",
            initialDelayString = "${voktrader.order-layer.reconciliation.interval-ms:2000}"
    )
    public void resumePendingCancellations() {
        if (!properties.isEnabled() || !properties.getReconciliation().isEnabled()) {
            return;
        }
        int resumed = cancellationService.resumePendingCancellations();
        if (resumed > 0) {
            log.info("Order layer resumed pending cancellations count={}", resumed);
        }
    }
}
