package org.earthsworth.wmatcher.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

class SearchMatcherTest {
    @Test
    void exposesTheSupportedOptionsInMenuOrder() {
        assertThat(List.of(SearchMatcher.values()).stream().map(SearchMatcher::identifier)).containsExactly(
                "contains",
                "containsIgnoreCase",
                "endswith",
                "endswithIgnoreCase",
                "equals",
                "equalsIgnoreCase",
                "matches",
                "matchesPartially",
                "startsWith",
                "startsWithIgnoreCase");
    }

    @Test
    void appliesLiteralMatchersWithTheRequestedCaseSensitivity() {
        assertThat(SearchMatcher.CONTAINS.predicate("Needle").test("aNeedlez")).isTrue();
        assertThat(SearchMatcher.CONTAINS.predicate("needle").test("aNeedlez")).isFalse();
        assertThat(SearchMatcher.CONTAINS_IGNORE_CASE.predicate("needle").test("aNeedlez")).isTrue();
        assertThat(SearchMatcher.ENDS_WITH.predicate("Needle").test("aNeedle")).isTrue();
        assertThat(SearchMatcher.ENDS_WITH_IGNORE_CASE.predicate("needle").test("aNeedle")).isTrue();
        assertThat(SearchMatcher.EQUALS.predicate("Needle").test("Needle")).isTrue();
        assertThat(SearchMatcher.EQUALS_IGNORE_CASE.predicate("needle").test("Needle")).isTrue();
        assertThat(SearchMatcher.STARTS_WITH.predicate("Needle").test("Needlez")).isTrue();
        assertThat(SearchMatcher.STARTS_WITH_IGNORE_CASE.predicate("needle").test("Needlez")).isTrue();
    }

    @Test
    void distinguishesFullAndPartialRegularExpressionMatches() {
        assertThat(SearchMatcher.MATCHES.predicate("Need.*").test("Needlez")).isTrue();
        assertThat(SearchMatcher.MATCHES.predicate("Need").test("aNeedlez")).isFalse();
        assertThat(SearchMatcher.MATCHES_PARTIALLY.predicate("Need").test("aNeedlez")).isTrue();
        assertThatThrownBy(() -> SearchMatcher.MATCHES.predicate("["))
                .isInstanceOf(PatternSyntaxException.class);
    }
}
