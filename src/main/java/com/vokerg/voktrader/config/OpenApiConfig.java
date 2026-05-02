package com.vokerg.voktrader.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI voktraderOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Voktrader API")
                        .version("0.0.1")
                        .description("Local API for bot configuration and runtime controls."));
    }
}
