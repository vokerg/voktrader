package com.vokerg.voktrader.trade;

import java.util.Optional;

public final class OrderGatewayContext {
    private static final ThreadLocal<OrderGateway> CURRENT = new ThreadLocal<>();

    private OrderGatewayContext() {
    }

    public static Optional<OrderGateway> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void runWith(OrderGateway gateway, Runnable runnable) {
        OrderGateway previous = CURRENT.get();
        CURRENT.set(gateway);
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
