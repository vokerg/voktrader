package com.vokerg.voktrader.executor;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class PythonExecutorClientOrderManagementTest {
    @Test
    void disabledClientReturnsStructuredErrorsForOrderManagementCalls() {
        ExecutorProperties properties = new ExecutorProperties();
        PythonExecutorClient client = new PythonExecutorClient(properties, WebClient.builder());

        ExecutorCancelOrderResponse cancel = client.cancelOrder("remote-1");
        ExecutorOrderStatusResponse status = client.getOrderStatus("remote-1");
        ExecutorOpenOrdersResponse openOrders = client.listOpenOrders("market-id", "token-id");
        ExecutorFillsResponse fills = client.listFills("remote-1", "market-id", "token-id", null);

        assertThat(cancel.success()).isFalse();
        assertThat(cancel.error().type()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(status.success()).isFalse();
        assertThat(status.error().type()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(openOrders.success()).isFalse();
        assertThat(openOrders.error().type()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(fills.success()).isFalse();
        assertThat(fills.error().type()).isEqualTo("UNSUPPORTED_OPERATION");
    }
}
