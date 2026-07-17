package org.earthsworth.wmatcher.core.model;

import java.util.Objects;
import java.util.Set;

public record DiffNode(
        String key,
        String displayName,
        EntityKind kind,
        EntityId left,
        EntityId right,
        Set<ChangeKind> changes) {
    public DiffNode {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(kind, "kind");
        changes = Set.copyOf(changes);
    }

    public boolean changed() {
        return !changes.isEmpty();
    }
}
