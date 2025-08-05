package be.esmay.atlas.base.scaler;

import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@UtilityClass
public final class MetadataConditionParser {

    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final int MAX_PATTERN_CACHE_SIZE = 100;

    public static boolean evaluate(String expression, Map<String, String> metadata) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }

        String trimmed = expression.trim();
        if (trimmed.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("Expression too long: " + trimmed.length() + " > " + MAX_EXPRESSION_LENGTH);
        }

        if (metadata == null) {
            metadata = new ConcurrentHashMap<>();
        }

        return parseExpression(trimmed, metadata);
    }

    private static boolean parseExpression(String expression, Map<String, String> metadata) {
        expression = expression.trim();

        int orIndex = findOperatorIndex(expression, " OR ");
        if (orIndex != -1) {
            String left = expression.substring(0, orIndex).trim();
            String right = expression.substring(orIndex + 4).trim();
            return parseExpression(left, metadata) || parseExpression(right, metadata);
        }

        int andIndex = findOperatorIndex(expression, " AND ");
        if (andIndex != -1) {
            String left = expression.substring(0, andIndex).trim();
            String right = expression.substring(andIndex + 5).trim();
            return parseExpression(left, metadata) && parseExpression(right, metadata);
        }

        if (expression.startsWith("(") && expression.endsWith(")")) {
            return parseExpression(expression.substring(1, expression.length() - 1), metadata);
        }

        if (expression.startsWith("NOT ")) {
            return !parseExpression(expression.substring(4), metadata);
        }

        return evaluateCondition(expression, metadata);
    }

    private static int findOperatorIndex(String expression, String operator) {
        int parenthesesLevel = 0;
        int index = 0;
        
        while (index <= expression.length() - operator.length()) {
            char ch = expression.charAt(index);
            if (ch == '(') {
                parenthesesLevel++;
            } else if (ch == ')') {
                parenthesesLevel--;
            } else if (parenthesesLevel == 0 && expression.substring(index).startsWith(operator)) {
                return index;
            }
            index++;
        }
        
        return -1;
    }

    private static boolean evaluateCondition(String condition, Map<String, String> metadata) {
        condition = condition.trim();

        if (condition.contains("!=")) {
            String[] parts = condition.split("!=", 2);
            String key = parts[0].trim();
            String expectedValue = parts[1].trim();
            String actualValue = metadata.get(key);
            return actualValue == null || !actualValue.equals(expectedValue);
        }

        if (condition.contains("~=")) {
            String[] parts = condition.split("~=", 2);
            String key = parts[0].trim();
            String regex = parts[1].trim();
            String actualValue = metadata.get(key);
            return actualValue != null && matchesRegexSafely(actualValue, regex);
        }

        if (condition.contains(">=")) {
            String[] parts = condition.split(">=", 2);
            String key = parts[0].trim();
            String expectedValue = parts[1].trim();
            return compareNumeric(metadata.get(key), expectedValue, ">=");
        }

        if (condition.contains("<=")) {
            String[] parts = condition.split("<=", 2);
            String key = parts[0].trim();
            String expectedValue = parts[1].trim();
            return compareNumeric(metadata.get(key), expectedValue, "<=");
        }

        if (condition.contains(">")) {
            String[] parts = condition.split(">", 2);
            String key = parts[0].trim();
            String expectedValue = parts[1].trim();
            return compareNumeric(metadata.get(key), expectedValue, ">");
        }

        if (condition.contains("<")) {
            String[] parts = condition.split("<", 2);
            String key = parts[0].trim();
            String expectedValue = parts[1].trim();
            return compareNumeric(metadata.get(key), expectedValue, "<");
        }

        if (condition.contains("=")) {
            String[] parts = condition.split("=", 2);
            String key = parts[0].trim();
            String expectedValue = parts[1].trim();
            String actualValue = metadata.get(key);
            return actualValue != null && actualValue.equals(expectedValue);
        }

        if (condition.startsWith("!")) {
            String key = condition.substring(1).trim();
            return !metadata.containsKey(key);
        }

        return metadata.containsKey(condition);
    }

    private static boolean compareNumeric(String actualValue, String expectedValue, String operator) {
        if (actualValue == null) {
            return false;
        }

        try {
            double actual = Double.parseDouble(actualValue);
            double expected = Double.parseDouble(expectedValue);

            switch (operator) {
                case ">" -> {
                    return actual > expected;
                }
                case "<" -> {
                    return actual < expected;
                }
                case ">=" -> {
                    return actual >= expected;
                }
                case "<=" -> {
                    return actual <= expected;
                }
                default -> {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean matchesRegexSafely(String actualValue, String regex) {
        if (regex.length() > 100) {
            throw new IllegalArgumentException("Regex pattern too long (max 100 chars): " + regex.length());
        }

        try {
            if (PATTERN_CACHE.size() >= MAX_PATTERN_CACHE_SIZE) {
                PATTERN_CACHE.clear();
            }

            Pattern pattern = PATTERN_CACHE.computeIfAbsent(regex, r -> {
                try {
                    return Pattern.compile(r);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("Invalid regex pattern: " + r, e);
                }
            });

            return pattern.matcher(actualValue).matches();
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + regex, e);
        }
    }
}