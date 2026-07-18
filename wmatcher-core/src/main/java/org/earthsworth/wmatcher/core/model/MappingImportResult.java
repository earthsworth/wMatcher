package org.earthsworth.wmatcher.core.model;

import java.util.List;
import java.util.Map;

public record MappingImportResult(
        Map<EntityId, EntityId> mappings,
        int skipped,
        List<String> warnings) {
    public MappingImportResult {
        mappings = Map.copyOf(mappings);
        warnings = List.copyOf(warnings);
    }

    public int imported() {
        return mappings.size();
    }
}
