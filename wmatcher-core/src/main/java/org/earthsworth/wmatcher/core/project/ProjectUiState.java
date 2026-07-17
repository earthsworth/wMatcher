package org.earthsworth.wmatcher.core.project;

import java.util.Set;

public record ProjectUiState(
        String search,
        Set<String> filters,
        String selectedKey,
        Set<String> expandedTreeKeys,
        int treeScrollPosition,
        int navigationDividerLocation) {
    private static final int DEFAULT_DIVIDER_LOCATION = 340;
    private static final Set<String> DEFAULT_EXPANDED_TREE_KEYS = Set.of(
            "status:changed",
            "status:changed:classes",
            "status:changed:members",
            "status:changed:resources",
            "status:unmatched",
            "status:unmatched:classes",
            "status:unmatched:members",
            "status:unmatched:resources");

    public ProjectUiState {
        search = search == null ? "" : search;
        filters = filters == null ? Set.of() : Set.copyOf(filters);
        selectedKey = selectedKey == null ? "" : selectedKey;
        expandedTreeKeys = expandedTreeKeys == null
                ? DEFAULT_EXPANDED_TREE_KEYS : Set.copyOf(expandedTreeKeys);
        treeScrollPosition = Math.max(0, treeScrollPosition);
        navigationDividerLocation = navigationDividerLocation > 0
                ? navigationDividerLocation : DEFAULT_DIVIDER_LOCATION;
    }

    public ProjectUiState(String search, Set<String> filters, String selectedKey) {
        this(search, filters, selectedKey, DEFAULT_EXPANDED_TREE_KEYS, 0, DEFAULT_DIVIDER_LOCATION);
    }

    public static ProjectUiState empty() {
        return new ProjectUiState("", Set.of(), "", DEFAULT_EXPANDED_TREE_KEYS, 0, DEFAULT_DIVIDER_LOCATION);
    }
}
