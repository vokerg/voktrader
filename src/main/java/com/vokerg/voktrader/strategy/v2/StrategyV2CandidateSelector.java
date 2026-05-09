package com.vokerg.voktrader.strategy.v2;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class StrategyV2CandidateSelector {
    public Optional<StrategyV2FeatureContext> select(
            StrategyV2Properties.Strategy strategy,
            List<StrategyV2FeatureContext> contexts
    ) {
        StrategyV2Properties.CandidateSelection selection = strategy.getCandidateSelection();
        List<StrategyV2FeatureContext> candidates = contexts.stream()
                .filter(context -> selection.getCandidates().isEmpty()
                        || selection.getCandidates().stream().anyMatch(outcome -> outcome.equalsIgnoreCase(context.candidate().outcome())))
                .toList();
        return switch (selection.getType() == null ? "higher_mid" : selection.getType()) {
            case "fixed_outcome" -> candidates.stream()
                    .filter(context -> selection.getOutcome() != null && selection.getOutcome().equalsIgnoreCase(context.candidate().outcome()))
                    .findFirst();
            case "lower_ask" -> maxOrMin(candidates, "candidate.ask", false);
            case "stronger_momentum" -> maxOrMin(candidates, "candidate." + nullSafe(selection.getFeature(), "mid_move_5s"), true);
            case "score" -> score(selection, candidates);
            default -> maxOrMin(candidates, "candidate.mid", true);
        };
    }

    private Optional<StrategyV2FeatureContext> score(
            StrategyV2Properties.CandidateSelection selection,
            List<StrategyV2FeatureContext> candidates
    ) {
        StrategyV2FeatureContext best = null;
        BigDecimal bestScore = null;
        for (StrategyV2FeatureContext context : candidates) {
            StrategyV2Properties.ScoreExpression expression = selection.getScore().get(context.candidate().outcome());
            BigDecimal score = expression == null ? BigDecimal.ZERO : ExpressionEvaluator.evaluate(expression.getExpression(), context.features());
            if (bestScore == null || score.compareTo(bestScore) > 0) {
                best = context;
                bestScore = score;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        if (selection.getMinScore() != null && bestScore.compareTo(selection.getMinScore()) < 0) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private Optional<StrategyV2FeatureContext> maxOrMin(List<StrategyV2FeatureContext> contexts, String feature, boolean max) {
        Comparator<StrategyV2FeatureContext> comparator = Comparator.comparing(context -> decimal(context.features().get(feature)));
        return contexts.stream()
                .filter(context -> context.features().get(feature) != null)
                .min(max ? comparator.reversed() : comparator);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String string && !string.isBlank()) {
            return new BigDecimal(string);
        }
        return BigDecimal.ZERO;
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
