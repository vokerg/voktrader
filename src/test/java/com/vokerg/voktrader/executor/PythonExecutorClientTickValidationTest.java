package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.marketdata.TickSizeService;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PythonExecutorClientTickValidationTest {
    @Test
    void invalidTickCombinationNeverBuildsAnHttpClient() {
        ExecutorProperties properties = new ExecutorProperties();
        properties.setEnabled(true);
        WebClient.Builder webClientBuilder = mock(WebClient.Builder.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TickSizeService tickSizeService = mock(TickSizeService.class);
        when(tickSizeService.validate("token-1", new BigDecimal("0.501")))
                .thenReturn(TickSizeService.TickValidation.rejected(
                        new BigDecimal("0.01"),
                        "price 0.501 is not aligned to tick 0.01 for tokenId=token-1"
                ));
        PythonExecutorClient client = new PythonExecutorClient(
                properties,
                webClientBuilder,
                objectMapper,
                tickSizeService
        );

        ExecutorOrderResponse response = client.submit(command());

        assertThat(response.accepted()).isFalse();
        assertThat(response.safeMessage()).contains("rejected before HTTP", "not aligned to tick 0.01");
        verifyNoInteractions(webClientBuilder);
    }

    private ExecutorOrderCommand command() {
        return new ExecutorOrderCommand(
                "idempotency-1",
                "strategy-1",
                "rule-1",
                "market-1",
                "slug",
                "question",
                "condition-1",
                "token-1",
                "Up",
                TradeSide.BUY,
                new BigDecimal("1.00"),
                null,
                new BigDecimal("0.501"),
                "FOK",
                false,
                true,
                Instant.parse("2026-07-25T12:00:00Z")
        );
    }
}
