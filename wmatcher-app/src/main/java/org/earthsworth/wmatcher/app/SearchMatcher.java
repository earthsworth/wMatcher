package org.earthsworth.wmatcher.app;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public enum SearchMatcher {
    CONTAINS("contains"),
    CONTAINS_IGNORE_CASE("containsIgnoreCase"),
    ENDS_WITH("endswith"),
    ENDS_WITH_IGNORE_CASE("endswithIgnoreCase"),
    EQUALS("equals"),
    EQUALS_IGNORE_CASE("equalsIgnoreCase"),
    MATCHES("matches"),
    MATCHES_PARTIALLY("matchesPartially"),
    STARTS_WITH("startsWith"),
    STARTS_WITH_IGNORE_CASE("startsWithIgnoreCase");

    private final String identifier;

    SearchMatcher(String identifier) {
        this.identifier = identifier;
    }

    public String identifier() {
        return identifier;
    }

    @Override
    public String toString() {
        return identifier;
    }

    public Predicate<String> predicate(String query) {
        String expected = Objects.requireNonNullElse(query, "");
        return switch (this) {
            case CONTAINS -> value -> value != null && value.contains(expected);
            case CONTAINS_IGNORE_CASE -> value -> value != null && containsIgnoreCase(value, expected);
            case ENDS_WITH -> value -> value != null && value.endsWith(expected);
            case ENDS_WITH_IGNORE_CASE -> value -> value != null && endsWithIgnoreCase(value, expected);
            case EQUALS -> value -> value != null && value.equals(expected);
            case EQUALS_IGNORE_CASE -> value -> value != null && value.equalsIgnoreCase(expected);
            case MATCHES -> nullSafe(Pattern.compile(expected).asMatchPredicate());
            case MATCHES_PARTIALLY -> nullSafe(Pattern.compile(expected).asPredicate());
            case STARTS_WITH -> value -> value != null && value.startsWith(expected);
            case STARTS_WITH_IGNORE_CASE -> value -> value != null && startsWithIgnoreCase(value, expected);
        };
    }

    private static boolean containsIgnoreCase(String value, String expected) {
        int lastStart = value.length() - expected.length();
        for (int start = 0; start <= lastStart; start++) {
            if (value.regionMatches(true, start, expected, 0, expected.length())) return true;
        }
        return false;
    }

    private static boolean endsWithIgnoreCase(String value, String expected) {
        int start = value.length() - expected.length();
        return start >= 0 && value.regionMatches(true, start, expected, 0, expected.length());
    }

    private static boolean startsWithIgnoreCase(String value, String expected) {
        return value.regionMatches(true, 0, expected, 0, expected.length());
    }

    private static Predicate<String> nullSafe(Predicate<String> predicate) {
        return value -> value != null && predicate.test(value);
    }
}
