package org.earthsworth.wmatcher.core.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record MappingOverrides(Map<EntityId, EntityId> locked) {
    public static final MappingOverrides EMPTY = new MappingOverrides(Map.of());

    public MappingOverrides {
        Map<EntityId, EntityId> copy = new LinkedHashMap<>(locked);
        Set<EntityId> right = new HashSet<>();
        for (Map.Entry<EntityId, EntityId> entry : copy.entrySet()) {
            if (entry.getKey().kind() != entry.getValue().kind() || !right.add(entry.getValue())) {
                throw new IllegalArgumentException("Mappings must be type-compatible and one-to-one");
            }
        }
        locked = Collections.unmodifiableMap(copy);
    }
}
