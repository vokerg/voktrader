package com.vokerg.voktrader.bot;

import java.util.Locale;

public enum BotAsset {
    BTC("btc", "bitcoin"),
    ETH("eth", "ethereum"),
    SOL("sol", "solana");

    private final String slugPrefix;
    private final String searchName;

    BotAsset(String slugPrefix, String searchName) {
        this.slugPrefix = slugPrefix;
        this.searchName = searchName;
    }

    public String slugPrefix() {
        return slugPrefix;
    }

    public String searchName() {
        return searchName;
    }

    public static BotAsset fromCode(String value) {
        if (value == null || value.isBlank()) {
            return BTC;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ETHERIUM".equals(normalized)) {
            normalized = "ETH";
        }
        if ("ETHEREUM".equals(normalized)) {
            normalized = "ETH";
        }
        if ("BITCOIN".equals(normalized)) {
            normalized = "BTC";
        }
        if ("SOLANA".equals(normalized)) {
            normalized = "SOL";
        }
        return BotAsset.valueOf(normalized);
    }
}
