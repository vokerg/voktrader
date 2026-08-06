package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.marketdata.TickSizeService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Primary executor client bean that suspends any ambient database transaction
 * for every remote side effect or remote read.
 *
 * <p>The durable order worker normally invokes the client without a transaction.
 * This boundary also protects legacy cancellation and reconciliation adapters
 * from accidentally holding an active transaction across network I/O.</p>
 */
@Primary
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TransactionDetachedPythonExecutorClient extends PythonExecutorClient {
    public TransactionDetachedPythonExecutorClient(
            ExecutorProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            TickSizeService tickSizeService
    ) {
        super(properties, webClientBuilder, objectMapper, tickSizeService);
    }
}
