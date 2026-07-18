package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JComboBox;
import org.earthsworth.wmatcher.app.SearchMatcher;

final class SearchMatcherSelect extends JComboBox<SearchMatcher> {
    private final Consumer<SearchMatcher> listener;

    SearchMatcherSelect(Consumer<SearchMatcher> listener) {
        super(SearchMatcher.values());
        this.listener = Objects.requireNonNull(listener);
        setSelectedItem(SearchMatcher.CONTAINS_IGNORE_CASE);
        getAccessibleContext().setAccessibleName(text("search.matcher"));
        updateTooltip();
        addActionListener(event -> {
            updateTooltip();
            this.listener.accept(matcher());
        });
    }

    SearchMatcher matcher() {
        SearchMatcher selected = (SearchMatcher) getSelectedItem();
        return selected == null ? SearchMatcher.CONTAINS_IGNORE_CASE : selected;
    }

    private void updateTooltip() {
        setToolTipText(text("search.matcherTooltip", matcher().identifier()));
    }
}
