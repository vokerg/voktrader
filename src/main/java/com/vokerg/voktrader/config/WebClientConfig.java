package com.vokerg.voktrader.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    @Qualifier("gammaWebClient")
    public WebClient gammaWebClient(
            WebClient.Builder builder,
            PolymarketProperties properties
    ) {
        return builder
                .baseUrl(properties.gammaBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(connector(properties))
                .build();
    }

    @Bean
    @Qualifier("clobWebClient")
    public WebClient clobWebClient(
            WebClient.Builder builder,
            PolymarketProperties properties
    ) {
        return builder
                .baseUrl(properties.clobBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(connector(properties))
                .build();
    }

    private ReactorClientHttpConnector connector(PolymarketProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.timeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

        return new ReactorClientHttpConnector(httpClient);
    }
}