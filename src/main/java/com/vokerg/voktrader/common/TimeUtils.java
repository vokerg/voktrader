package com.vokerg.voktrader.common;

import java.time.Instant;

public final class TimeUtils {

    private TimeUtils() {
    }

    public static Instant now() {
        return Instant.now();
    }
}
