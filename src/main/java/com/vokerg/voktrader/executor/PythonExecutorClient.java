package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.marketdata.TickSizeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Component
public class PythonExecutorClient {
    private static final Logger log = LoggerFactory.getLogger(PythonExecutorClient.class);

    private final ExecutorProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final TickSizeService tickSizeService;

    public PythonExecutorClient(
            ExecutorProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            TickSizeService tickSizeService
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.tickSizeService = tickSizeService;
    }

    public ExecutorOrderResponse submit(ExecutorOrderCommand command) {
        if (command == null) {
            return ExecutorOrderResponse.rejected("Executor submission rejected before HTTP: command is required");
        }
        if (!properties.isEnabled()) {
            return ExecutorOrderResponse.rejected("Python executor is disabled: set voktrader.executor.enabled=true");
        }
        TickSizeService.TickValidation tickValidation = tickSizeService.validate(command.tokenId(), command.limitPrice());
        if (!tickValidation.valid()) {
            return ExecutorOrderResponse.rejected(
                    "Executor submission rejected before HTTP: " + tickValidation.reason()
            );
        }

        try {
            String body = authedClient()
                    .post()
                    .uri("/v1/orders")
                    .bodyValue(command)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.getTimeout())
                    .block();
            if (body == null || body.isBlank()) {
                throw ExecutorSubmissionException.transport(
                        ExecutorSubmissionFailureType.MALFORMED_RESPONSE,
                        "Python executor returned an empty submission response",
                        null
                );
            }
            try {
                ExecutorOrderResponse response = objectMapper.readValue(body, ExecutorOrderResponse.class);
                return copyWithRawResponse(response, body);
            } catch (JacksonException exception) {
                throw new ExecutorSubmissionException(
                        ExecutorSubmissionFailureType.MALFORMED_RESPONSE,
                        "Python executor returned malformed submission JSON",
                        null,
                        body,
                        exception
                );
            }
        } catch (ExecutorSubmissionException exception) {
            throw exception;
        } catch (WebClientResponseException exception) {
            String body = exception.getResponseBodyAsString();
            int status = exception.getStatusCode().value();
            log.warn("Python executor order response: status={} body={}", status, body);
            if (status == 425) {
                throw ExecutorSubmissionException.http(
                        ExecutorSubmissionFailureType.HTTP_425_TOO_EARLY,
                        exception.getStatusCode(),
                        body,
                        exception
                );
            }
            if (status == 503) {
                throw ExecutorSubmissionException.http(
                        ExecutorSubmissionFailureType.HTTP_503_SERVICE_UNAVAILABLE,
                        exception.getStatusCode(),
                        body,
                        exception
                );
            }
            if (status >= 500) {
                throw ExecutorSubmissionException.http(
                        ExecutorSubmissionFailureType.EXECUTOR_CRASH,
                        exception.getStatusCode(),
                        body,
                        exception
                );
            }
            return ExecutorOrderResponse.rejected("Python executor HTTP " + status + ": " + body);
        } catch (WebClientRequestException exception) {
            throw classifyTransportFailure(exception);
        } catch (Exception exception) {
            ExecutorSubmissionFailureType type = hasCause(exception, TimeoutException.class)
                    ? ExecutorSubmissionFailureType.TIMEOUT
                    : ExecutorSubmissionFailureType.UNKNOWN_FAILURE;
            throw ExecutorSubmissionException.transport(
                    type,
                    "Python executor submission failed: " + safeMessage(exception),
                    exception
            );
        }
    }

    private ExecutorSubmissionException classifyTransportFailure(WebClientRequestException exception) {
        String reason = requestFailureReason(exception);
        String normalized = reason.toLowerCase(Locale.ROOT);
        ExecutorSubmissionFailureType type;
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            type = ExecutorSubmissionFailureType.TIMEOUT;
        } else if (normalized.contains("connection reset")
                || normalized.contains("broken pipe")
                || normalized.contains("prematurely closed")) {
            type = ExecutorSubmissionFailureType.CONNECTION_RESET;
        } else if (normalized.contains("connection refused")
                || normalized.contains("failed to connect")) {
            type = ExecutorSubmissionFailureType.EXECUTOR_CRASH;
        } else {
            type = ExecutorSubmissionFailureType.NETWORK_FAILURE;
        }
        return ExecutorSubmissionException.transport(
                type,
                "Python executor unavailable at " + properties.getBaseUrl() + ": " + reason,
                exception
        );
    }

    private ExecutorOrderResponse copyWithRawResponse(ExecutorOrderResponse response, String body) {
        return new ExecutorOrderResponse(
                response.accepted(), response.filled(), response.status(), response.exchangeOrderId(),
                response.averagePrice(), response.filledShares(), response.filledAmountUsd(), response.feeUsd(),
                response.message(), body, response.exchangeTimestamp()
        );
    }

    public ExecutorCapabilitiesResponse capabilities() {
        if (!properties.isEnabled()) {
            return ExecutorCapabilitiesResponse.failure("UNSUPPORTED_OPERATION", disabledMessage());
        }
        try {
            ExecutorCapabilitiesResponse response = authedClient().get().uri("/v1/capabilities").retrieve()
                    .bodyToMono(ExecutorCapabilitiesResponse.class).timeout(properties.getTimeout()).block();
            return response == null
                    ? ExecutorCapabilitiesResponse.failure("UNKNOWN_RESPONSE", "Python executor returned an empty capability response")
                    : response;
        } catch (WebClientResponseException e) {
            log.warn("Python executor capabilities rejected: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return ExecutorCapabilitiesResponse.failure("EXCHANGE_REJECTION", "Python executor HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (WebClientRequestException e) {
            String reason = requestFailureReason(e);
            log.warn("Python executor unavailable for capabilities at {}: {}", properties.getBaseUrl(), reason);
            return ExecutorCapabilitiesResponse.failure("NETWORK_FAILURE", "Python executor unavailable at " + properties.getBaseUrl() + ": " + reason);
        } catch (Exception e) {
            log.warn("Python executor capability call failed", e);
            return ExecutorCapabilitiesResponse.failure("UNKNOWN_RESPONSE", "Python executor capabilities failed: " + e.getMessage());
        }
    }

    public ExecutorCancelOrderResponse cancelOrder(String remoteOrderId) {
        if (!properties.isEnabled()) {
            return ExecutorCancelOrderResponse.failure(remoteOrderId, "UNSUPPORTED_OPERATION", disabledMessage());
        }
        try {
            return authedClient().post().uri("/v1/orders/{orderId}/cancel", remoteOrderId).retrieve()
                    .bodyToMono(ExecutorCancelOrderResponse.class).timeout(properties.getTimeout()).block();
        } catch (WebClientResponseException e) {
            log.warn("Python executor cancel rejected: orderId={} status={} body={}", remoteOrderId, e.getStatusCode(), e.getResponseBodyAsString());
            return ExecutorCancelOrderResponse.failure(remoteOrderId, "EXCHANGE_REJECTION", "Python executor HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (WebClientRequestException e) {
            String reason = requestFailureReason(e);
            log.warn("Python executor unavailable for cancel at {}: {}", properties.getBaseUrl(), reason);
            return ExecutorCancelOrderResponse.failure(remoteOrderId, "NETWORK_FAILURE", "Python executor unavailable at " + properties.getBaseUrl() + ": " + reason);
        } catch (Exception e) {
            log.warn("Python executor cancel call failed orderId={}", remoteOrderId, e);
            return ExecutorCancelOrderResponse.failure(remoteOrderId, "UNKNOWN_RESPONSE", "Python executor cancel failed: " + e.getMessage());
        }
    }

    public ExecutorOrderStatusResponse getOrderStatus(String remoteOrderId) {
        if (!properties.isEnabled()) {
            return ExecutorOrderStatusResponse.failure(remoteOrderId, "UNSUPPORTED_OPERATION", disabledMessage());
        }
        try {
            return authedClient().get().uri("/v1/orders/{orderId}", remoteOrderId).retrieve()
                    .bodyToMono(ExecutorOrderStatusResponse.class).timeout(properties.getTimeout()).block();
        } catch (WebClientResponseException e) {
            log.warn("Python executor order status rejected: orderId={} status={} body={}", remoteOrderId, e.getStatusCode(), e.getResponseBodyAsString());
            return ExecutorOrderStatusResponse.failure(remoteOrderId, "EXCHANGE_REJECTION", "Python executor HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (WebClientRequestException e) {
            String reason = requestFailureReason(e);
            log.warn("Python executor unavailable for order status at {}: {}", properties.getBaseUrl(), reason);
            return ExecutorOrderStatusResponse.failure(remoteOrderId, "NETWORK_FAILURE", "Python executor unavailable at " + properties.getBaseUrl() + ": " + reason);
        } catch (Exception e) {
            log.warn("Python executor order status call failed orderId={}", remoteOrderId, e);
            return ExecutorOrderStatusResponse.failure(remoteOrderId, "UNKNOWN_RESPONSE", "Python executor order status failed: " + e.getMessage());
        }
    }

    public ExecutorOpenOrdersResponse listOpenOrders(String marketId, String tokenId) {
        if (!properties.isEnabled()) {
            return ExecutorOpenOrdersResponse.failure("UNSUPPORTED_OPERATION", disabledMessage());
        }
        try {
            return authedClient().get().uri(optionalFilters("/v1/orders/open", marketId, tokenId, null, null, null, null, null)).retrieve()
                    .bodyToMono(ExecutorOpenOrdersResponse.class).timeout(properties.getTimeout()).block();
        } catch (WebClientResponseException e) {
            log.warn("Python executor open orders rejected: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return ExecutorOpenOrdersResponse.failure("EXCHANGE_REJECTION", "Python executor HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (WebClientRequestException e) {
            String reason = requestFailureReason(e);
            log.warn("Python executor unavailable for open orders at {}: {}", properties.getBaseUrl(), reason);
            return ExecutorOpenOrdersResponse.failure("NETWORK_FAILURE", "Python executor unavailable at " + properties.getBaseUrl() + ": " + reason);
        } catch (Exception e) {
            log.warn("Python executor open orders call failed", e);
            return ExecutorOpenOrdersResponse.failure("UNKNOWN_RESPONSE", "Python executor open orders failed: " + e.getMessage());
        }
    }

    public ExecutorFillsResponse listFills(
            String remoteOrderId, String marketId, String tokenId,
            com.vokerg.voktrader.trade.model.TradeSide side,
            java.math.BigDecimal price, java.math.BigDecimal shares, Instant since
    ) {
        if (!properties.isEnabled()) {
            return ExecutorFillsResponse.failure("UNSUPPORTED_OPERATION", disabledMessage());
        }
        try {
            return authedClient().get().uri(optionalFilters("/v1/fills", marketId, tokenId, remoteOrderId, side, price, shares, since)).retrieve()
                    .bodyToMono(ExecutorFillsResponse.class).timeout(properties.getTimeout()).block();
        } catch (WebClientResponseException e) {
            log.warn("Python executor fills rejected: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return ExecutorFillsResponse.failure("EXCHANGE_REJECTION", "Python executor HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (WebClientRequestException e) {
            String reason = requestFailureReason(e);
            log.warn("Python executor unavailable for fills at {}: {}", properties.getBaseUrl(), reason);
            return ExecutorFillsResponse.failure("NETWORK_FAILURE", "Python executor unavailable at " + properties.getBaseUrl() + ": " + reason);
        } catch (Exception e) {
            log.warn("Python executor fills call failed", e);
            return ExecutorFillsResponse.failure("UNKNOWN_RESPONSE", "Python executor fills failed: " + e.getMessage());
        }
    }

    private WebClient authedClient() {
        return webClientBuilder.baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken()).build();
    }

    private Function<UriBuilder, URI> optionalFilters(
            String path, String marketId, String tokenId, String remoteOrderId,
            com.vokerg.voktrader.trade.model.TradeSide side,
            java.math.BigDecimal price, java.math.BigDecimal shares, Instant since
    ) {
        return builder -> {
            UriBuilder uriBuilder = builder.path(path);
            if (marketId != null && !marketId.isBlank()) uriBuilder.queryParam("market_id", marketId);
            if (tokenId != null && !tokenId.isBlank()) uriBuilder.queryParam("token_id", tokenId);
            if (remoteOrderId != null && !remoteOrderId.isBlank()) uriBuilder.queryParam("order_id", remoteOrderId);
            if (side != null) uriBuilder.queryParam("side", side.name());
            if (price != null) uriBuilder.queryParam("price", price);
            if (shares != null) uriBuilder.queryParam("shares", shares);
            if (since != null) uriBuilder.queryParam("since", since.toString());
            return uriBuilder.build();
        };
    }

    private String disabledMessage() {
        return "Python executor is disabled: set voktrader.executor.enabled=true";
    }

    private String requestFailureReason(WebClientRequestException e) {
        return e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
