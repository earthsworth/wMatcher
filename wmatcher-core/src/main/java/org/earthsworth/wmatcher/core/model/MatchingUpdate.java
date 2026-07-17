package org.earthsworth.wmatcher.core.model;

import java.util.Set;

/** Incremental matching result and the entity closure that changed. */
public record MatchingUpdate(MatchResult result, Set<EntityId> affectedEntities, boolean fullRefresh) {
    public MatchingUpdate {
        affectedEntities = Set.copyOf(affectedEntities);
    }
}
