package com.vokerg.voktrader.strategy.v2;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

final class ExpressionEvaluator {
    private ExpressionEvaluator() {
    }

    static BigDecimal evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        return new Parser(expression, variables).parse();
    }

    private static final class Parser {
        private final String text;
        private final Map<String, Object> variables;
        private int index;

        private Parser(String text, Map<String, Object> variables) {
            this.text = text;
            this.variables = variables;
        }

        private BigDecimal parse() {
            BigDecimal value = expression();
            skipWhitespace();
            return value;
        }

        private BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    value = value.add(term());
                } else if (match('-')) {
                    value = value.subtract(term());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal value = factor();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    value = value.multiply(factor());
                } else if (match('/')) {
                    BigDecimal divisor = factor();
                    value = divisor.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : value.divide(divisor, MathContext.DECIMAL64);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal factor() {
            skipWhitespace();
            if (match('(')) {
                BigDecimal value = expression();
                match(')');
                return value;
            }
            if (peek("max")) {
                index += 3;
                match('(');
                BigDecimal left = expression();
                match(',');
                BigDecimal right = expression();
                match(')');
                return left.max(right);
            }
            if (current() == '-' || Character.isDigit(current())) {
                return number();
            }
            return variable();
        }

        private BigDecimal number() {
            int start = index;
            if (current() == '-') {
                index++;
            }
            while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) {
                index++;
            }
            return new BigDecimal(text.substring(start, index));
        }

        private BigDecimal variable() {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    index++;
                    continue;
                }
                break;
            }
            Object value = variables.get(text.substring(start, index));
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

        private boolean match(char expected) {
            skipWhitespace();
            if (current() == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean peek(String expected) {
            skipWhitespace();
            return text.regionMatches(index, expected, 0, expected.length());
        }

        private char current() {
            return index >= text.length() ? '\0' : text.charAt(index);
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
