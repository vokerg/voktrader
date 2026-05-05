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
     * Human and agent readable description of the strategy.
     *
     * <p>Keep this intentionally verbose. Strategy descriptions are meant to help future authors,
     * including AI coding agents, understand when a strategy is appropriate, what data it depends on,
     * and where it is weak before changing or reusing it.</p>
     */
    StrategyDescription description();

    /**
     * One strategy evaluation cycle.
     */
    void tick();

    record StrategyDescription(
            String name,
            String status,
            String intent,
            String marketDataUsed,
            String entryLogic,
            String exitLogic,
            String strengths,
            String weakSides,
            String tuningNotes
    ) {
    }
}
