package com.vokerg.voktrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymarket")
public class PolymarketProperties {

    private String gammaBaseUrl = "https://gamma-api.polymarket.com";
    private String clobBaseUrl = "https://clob.polymarket.com";

    public String getGammaBaseUrl() {
        return gammaBaseUrl;
    }

    public void setGammaBaseUrl(String gammaBaseUrl) {
        this.gammaBaseUrl = gammaBaseUrl;
    }

    public String getClobBaseUrl() {
        return clobBaseUrl;
    }

    public void setClobBaseUrl(String clobBaseUrl) {
        this.clobBaseUrl = clobBaseUrl;
    }
}
