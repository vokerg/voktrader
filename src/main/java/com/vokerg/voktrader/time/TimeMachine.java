package com.vokerg.voktrader.time;

import java.time.Clock;
import java.time.Instant;

public final class TimeMachine {
    private static final ThreadLocal<Instant> CURRENT = new ThreadLocal<>();

    private TimeMachine() {
    }

    public static Instant now() {
        Instant current = CURRENT.get();
        return current == null ? Instant.now() : current;
    }

    public static Instant now(Clock fallback) {
        Instant current = CURRENT.get();
        return current == null ? fallback.instant() : current;
    }

    public static boolean isOverridden() {
        return CURRENT.get() != null;
    }

    public static void runAt(Instant instant, Runnable runnable) {
        Instant previous = CURRENT.get();
        CURRENT.set(instant);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
