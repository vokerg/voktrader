package com.vokerg.voktrader.strategy.v2;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class StrategyV2ConditionEvaluator {
    public boolean matches(StrategyV2FeatureContext context, StrategyV2Properties.Condition condition, StrategyV2FeatureResolver resolver) {
        if (condition == null) {
            return true;
        }
        if (condition.getAll() != null) {
            return condition.getAll().stream().allMatch(child -> matches(context, child, resolver));
        }
        if (condition.getAny() != null) {
            return condition.getAny().stream().anyMatch(child -> matches(context, child, resolver));
        }
        if (condition.getNot() != null) {
            return !matches(context, condition.getNot(), resolver);
        }
        Object actual = resolver.resolve(context, condition.getFeature());
        String op = condition.getOp() == null ? "exists" : condition.getOp();
        return compare(actual, op, condition.getValue(), condition.getValues());
    }

    private boolean compare(Object actual, String op, Object expected, List<Object> values) {
        return switch (op) {
            case "exists" -> actual != null;
            case "missing" -> actual == null;
            case "<" -> decimal(actual).compareTo(decimal(expected)) < 0;
            case "<=" -> decimal(actual).compareTo(decimal(expected)) <= 0;
            case ">" -> decimal(actual).compareTo(decimal(expected)) > 0;
            case ">=" -> decimal(actual).compareTo(decimal(expected)) >= 0;
            case "==" -> equal(actual, expected);
            case "!=" -> !equal(actual, expected);
            case "between" -> between(actual, values);
            case "outside" -> !between(actual, values);
            case "contains" -> actual != null && expected != null && actual.toString().contains(expected.toString());
            case "matches_regex" -> actual != null && expected != null && Pattern.compile(expected.toString()).matcher(actual.toString()).find();
            case "in" -> in(actual, values);
            case "not_in" -> !in(actual, values);
            default -> throw new IllegalArgumentException("Unsupported Strategy V2 condition operator: " + op);
        };
    }

    private boolean equal(Object actual, Object expected) {
        if (actual instanceof Boolean || expected instanceof Boolean) {
            return Boolean.parseBoolean(String.valueOf(actual)) == Boolean.parseBoolean(String.valueOf(expected));
        }
        if (isNumeric(actual) || isNumeric(expected)) {
            return decimal(actual).compareTo(decimal(expected)) == 0;
        }
        return Objects.equals(
                actual == null ? null : actual.toString(),
                expected == null ? null : expected.toString()
        );
    }

    private boolean between(Object actual, List<Object> values) {
        if (values == null || values.size() != 2) {
            return false;
        }
        BigDecimal value = decimal(actual);
        return value.compareTo(decimal(values.get(0))) >= 0 && value.compareTo(decimal(values.get(1))) <= 0;
    }

    private boolean in(Object actual, Collection<Object> values) {
        if (values == null) {
            return false;
        }
        return values.stream().anyMatch(value -> equal(actual, value));
    }

    private boolean isNumeric(Object value) {
        return value instanceof Number || value instanceof BigDecimal;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }
}
