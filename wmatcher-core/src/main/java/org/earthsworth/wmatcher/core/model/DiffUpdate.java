package org.earthsworth.wmatcher.core.model;

import java.util.Set;

/** Incremental difference result and the entity closure that changed. */
public record DiffUpdate(DiffResult result, Set<EntityId> affectedEntities, boolean fullRefresh) {
    public DiffUpdate {
        affectedEntities = Set.copyOf(affectedEntities);
    }
}
