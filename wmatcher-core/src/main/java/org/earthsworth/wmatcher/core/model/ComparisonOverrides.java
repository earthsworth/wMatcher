package org.earthsworth.wmatcher.core.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** User decisions that must take precedence over automatic matching. */
public record ComparisonOverrides(
        Map<EntityId, EntityId> lockedMappings,
        Set<EntityId> confirmedRemoved,
        Set<EntityId> confirmedAdded) {
    public static final ComparisonOverrides EMPTY = new ComparisonOverrides(Map.of(), Set.of(), Set.of());

    public ComparisonOverrides {
        Map<EntityId, EntityId> mappings = new LinkedHashMap<>(lockedMappings);
        Set<EntityId> right = new HashSet<>();
        for (Map.Entry<EntityId, EntityId> entry : mappings.entrySet()) {
            if (entry.getKey().kind() != entry.getValue().kind() || !right.add(entry.getValue())) {
                throw new IllegalArgumentException("Mappings must be type-compatible and one-to-one");
            }
        }
        Set<EntityId> removed = Set.copyOf(confirmedRemoved);
        Set<EntityId> added = Set.copyOf(confirmedAdded);
        if (mappings.keySet().stream().anyMatch(removed::contains)
                || mappings.values().stream().anyMatch(added::contains)) {
            throw new IllegalArgumentException("An entity cannot be both matched and confirmed as single-sided");
        }
        lockedMappings = Collections.unmodifiableMap(mappings);
        confirmedRemoved = removed;
        confirmedAdded = added;
    }

    public ComparisonOverrides(Map<EntityId, EntityId> lockedMappings) {
        this(lockedMappings, Set.of(), Set.of());
    }
}
