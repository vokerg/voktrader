package com.vokerg.voktrader.strategy;

/**
 * A trading strategy is a named unit of trading logic.
 *
 * <p>Strategies are regular Spring beans, but they do not schedule themselves.
 * BotRuntimeManager owns the schedule and invokes each enabled bot's configured strategy.</p>
 */
public interface TradingStrategy {

    /**
     * Stable config id used by voktrader.strategy.active.
     */
    String id();

    /**
     * One strategy evaluation cycle.
     */
    void tick();
}
