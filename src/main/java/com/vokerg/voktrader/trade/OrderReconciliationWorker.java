package com.vokerg.voktrader.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconciliationWorker {
    private final OrderLayerProperties properties;
    private final OrderManager orderManager;

    @Scheduled(
            fixedDelayString = "${voktrader.order-layer.reconciliation.interval-ms:2000}",
            initialDelayString = "${voktrader.order-layer.reconciliation.interval-ms:2000}"
    )
    public void reconcileOpenOrders() {
        if (!properties.isEnabled() || !properties.getReconciliation().isEnabled()) {
            return;
        }
        int reconciled = orderManager.reconcileOpenOrders(OrderReconciliationSource.AUTO_WORKER);
        if (reconciled > 0) {
            log.info("Order layer reconciled open orders count={}", reconciled);
        }
    }
}
