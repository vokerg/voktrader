package com.vokerg.voktrader.bot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum MarketFamily {
    BTC_5M(BotAsset.BTC, BotInterval.FIVE_MINUTES),
    BTC_15M(BotAsset.BTC, BotInterval.FIFTEEN_MINUTES),
    ETH_5M(BotAsset.ETH, BotInterval.FIVE_MINUTES),
    ETH_15M(BotAsset.ETH, BotInterval.FIFTEEN_MINUTES),
    SOL_5M(BotAsset.SOL, BotInterval.FIVE_MINUTES),
    SOL_15M(BotAsset.SOL, BotInterval.FIFTEEN_MINUTES);

    private final BotAsset asset;
    private final BotInterval interval;

    MarketFamily(BotAsset asset, BotInterval interval) {
        this.asset = asset;
        this.interval = interval;
    }

    public BotAsset asset() {
        return asset;
    }

    public BotInterval interval() {
        return interval;
    }

    public String intervalCode() {
        return interval.code();
    }

    public String slugForStartEpoch(long startEpoch) {
        return asset.slugPrefix() + "-updown-" + interval.code() + "-" + startEpoch;
    }

    public List<String> candidateSlugs(Instant now, int lookAheadWindows) {
        long step = interval.stepSeconds();
        long nowEpoch = now.getEpochSecond();
        long currentStart = (nowEpoch / step) * step;
        List<String> slugs = new ArrayList<>();
        for (int i = 0; i <= lookAheadWindows; i++) {
            slugs.add(slugForStartEpoch(currentStart + (i * step)));
        }
        return slugs;
    }

    public String searchQuery() {
        return asset.searchName() + " up or down " + interval.code();
    }

    public boolean matchesSlug(String slug) {
        if (slug == null) {
            return false;
        }
        String normalized = slug.toLowerCase(Locale.ROOT);
        return normalized.contains(asset.slugPrefix() + "-updown-" + interval.code() + "-")
                || normalized.contains(asset.searchName() + "-up-or-down-" + interval.code());
    }

    public static MarketFamily of(BotAsset asset, BotInterval interval) {
        for (MarketFamily family : values()) {
            if (family.asset == asset && family.interval == interval) {
                return family;
            }
        }
        throw new IllegalArgumentException("Unsupported market family asset=" + asset + " interval=" + interval);
    }

    public static MarketFamily fromCodes(String asset, String interval) {
        return of(BotAsset.fromCode(asset), BotInterval.fromCode(interval));
    }

    public static MarketFamily fromNameOrCodes(String family, String asset, String interval) {
        if (family != null && !family.isBlank()) {
            return MarketFamily.valueOf(family.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        return fromCodes(asset, interval);
    }
}
