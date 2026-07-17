package org.earthsworth.wmatcher.core.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record DiffResult(List<DiffNode> nodes, Map<ChangeKind, Long> counts) {
    public DiffResult {
        nodes = List.copyOf(nodes);
        EnumMap<ChangeKind, Long> copy = new EnumMap<>(ChangeKind.class);
        copy.putAll(counts);
        counts = Map.copyOf(copy);
    }
}
