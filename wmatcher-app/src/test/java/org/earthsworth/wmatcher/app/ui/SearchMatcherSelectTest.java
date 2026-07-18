package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.earthsworth.wmatcher.app.SearchMatcher;
import org.junit.jupiter.api.Test;

class SearchMatcherSelectTest {
    @Test
    void defaultsToContainsIgnoreCaseAndOffersEveryMatcher() {
        AtomicReference<SearchMatcher> selected = new AtomicReference<>();
        SearchMatcherSelect select = new SearchMatcherSelect(selected::set);

        assertThat(select.matcher()).isEqualTo(SearchMatcher.CONTAINS_IGNORE_CASE);
        assertThat(select.getItemCount()).isEqualTo(SearchMatcher.values().length);
        assertThat(select.getSelectedItem()).hasToString("containsIgnoreCase");

        select.setSelectedItem(SearchMatcher.EQUALS);

        assertThat(selected.get()).isEqualTo(SearchMatcher.EQUALS);
        assertThat(select.matcher()).isEqualTo(SearchMatcher.EQUALS);
        assertThat(select.getSelectedItem()).hasToString("equals");
    }
}
