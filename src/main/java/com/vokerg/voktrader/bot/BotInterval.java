package com.vokerg.voktrader.bot;

import java.util.Locale;

public enum BotInterval {
    FIVE_MINUTES("5m", 5 * 60L),
    FIFTEEN_MINUTES("15m", 15 * 60L);

    private final String code;
    private final long stepSeconds;

    BotInterval(String code, long stepSeconds) {
        this.code = code;
        this.stepSeconds = stepSeconds;
    }

    public String code() {
        return code;
    }

    public long stepSeconds() {
        return stepSeconds;
    }

    public static BotInterval fromCode(String value) {
        if (value == null || value.isBlank()) {
            return FIVE_MINUTES;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "5", "5m", "five_minutes", "five-minutes" -> FIVE_MINUTES;
            case "15", "15m", "fifteen_minutes", "fifteen-minutes" -> FIFTEEN_MINUTES;
            default -> throw new IllegalArgumentException("Unsupported bot interval: " + value);
        };
    }
}
