package com.vokerg.voktrader.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class PythonExecutorClient {
    private static final Logger log = LoggerFactory.getLogger(PythonExecutorClient.class);

    private final ExecutorProperties properties;
    private final WebClient.Builder webClientBuilder;

    public PythonExecutorClient(ExecutorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public ExecutorOrderResponse submit(ExecutorOrderCommand command) {
        if (!properties.isEnabled()) {
            return ExecutorOrderResponse.rejected("Python executor is disabled: set voktrader.executor.enabled=true");
        }

        try {
            return webClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .build()
                    .post()
                    .uri("/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken())
                    .bodyValue(command)
                    .retrieve()
                    .bodyToMono(ExecutorOrderResponse.class)
                    .timeout(properties.getTimeout())
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Python executor rejected order: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return ExecutorOrderResponse.rejected("Python executor HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (WebClientRequestException e) {
            String reason = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
            log.warn("Python executor unavailable at {}: {}", properties.getBaseUrl(), reason);
            return ExecutorOrderResponse.rejected("Python executor unavailable at " + properties.getBaseUrl() + ": " + reason);
        } catch (Exception e) {
            log.warn("Python executor call failed", e);
            return ExecutorOrderResponse.rejected("Python executor call failed: " + e.getMessage());
        }
    }
}
