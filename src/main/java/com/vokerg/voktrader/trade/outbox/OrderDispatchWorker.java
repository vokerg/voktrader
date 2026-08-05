package com.vokerg.voktrader.trade.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class OrderDispatchWorker {
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final OrderDispatchStateService stateService;
    private final PythonExecutorClient executorClient;
    private final ExecutorProperties executorProperties;
    private final OrderOutboxProperties properties;
    private final String workerId;

    public OrderDispatchWorker(
            OrderDispatchStateService stateService,
            PythonExecutorClient executorClient,
            ExecutorProperties executorProperties,
            OrderOutboxProperties properties
    ) {
        this.stateService = stateService;
        this.executorClient = executorClient;
        this.executorProperties = executorProperties;
        this.properties = properties;
        this.workerId = resolveWorkerId(properties.getWorkerId());
    }

    @Scheduled(
            fixedDelayString = "${voktrader.order-outbox.poll-ms:1000}",
            initialDelayString = "${voktrader.order-outbox.poll-ms:1000}"
    )
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        runOnce();
    }

    public int runOnce() {
        Instant cycleStartedAt = Instant.now();
        int reconciled = stateService.reconcileExpiredLeases(
                cycleStartedAt,
                properties.getBatchSize()
        );
        if (reconciled > 0) {
            log.warn(
                    "Moved expired order dispatch leases to reconciliation count={} workerId={}",
                    reconciled,
                    workerId
            );
        }
        if (!executorProperties.isEnabled()) {
            log.debug("Order outbox claim skipped because the Python executor is disabled");
            return 0;
        }

        int processed = 0;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            Optional<OrderDispatchClaim> next = stateService.claimNext(workerId, Instant.now());
            if (next.isEmpty()) {
                break;
            }
            dispatch(next.orElseThrow());
            processed++;
        }
        return processed;
    }

    private void dispatch(OrderDispatchClaim claim) {
        long startedNanos = System.nanoTime();
        ExecutorOrderResponse response;
        try {
            TradeIntent intent = deserialize(claim.payloadJson());
            ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(
                    intent,
                    claim.clientOrderId(),
                    claim.executionMode() != ExecutionMode.LIVE || executorProperties.isDryRun()
            );
            response = executorClient.submit(command);
        } catch (RuntimeException failure) {
            long submitRttMs = elapsedMillis(startedNanos);
            stateService.recordAmbiguousFailure(claim, failure, Instant.now());
            log.error(
                    "Order dispatch outcome requires reconciliation clientOrderId={} "
                            + "queueLatencyMs={} submitRttMs={} workerId={}",
                    claim.clientOrderId(),
                    queueLatencyMillis(claim),
                    submitRttMs,
                    workerId,
                    failure
            );
            return;
        }

        long submitRttMs = elapsedMillis(startedNanos);
        stateService.recordResponse(claim, response, Instant.now(), submitRttMs);
        log.info(
                "Order dispatch completed clientOrderId={} accepted={} status={} "
                        + "queueLatencyMs={} submitRttMs={} workerId={}",
                claim.clientOrderId(),
                response.accepted(),
                response.status(),
                queueLatencyMillis(claim),
                submitRttMs,
                workerId
        );
    }

    private TradeIntent deserialize(String payloadJson) {
        try {
            return PAYLOAD_MAPPER.readValue(payloadJson, TradeIntent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted order intent is not readable", exception);
        }
    }

    private static long queueLatencyMillis(OrderDispatchClaim claim) {
        return Math.max(0L, Duration.between(claim.acceptedAt(), claim.claimedAt()).toMillis());
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String resolveWorkerId(String configuredWorkerId) {
        if (configuredWorkerId != null && !configuredWorkerId.isBlank()) {
            return configuredWorkerId;
        }
        return hostname() + "-" + UUID.randomUUID();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "voktrader";
        }
    }
}
