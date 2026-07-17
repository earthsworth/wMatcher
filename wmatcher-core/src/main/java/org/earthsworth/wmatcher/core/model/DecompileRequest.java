package org.earthsworth.wmatcher.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DecompileRequest(
        ArtifactSnapshot artifact,
        String className,
        Map<EntityId, EntityId> canonicalMappings,
        boolean remapToTarget,
        List<Path> libraries) {
    public DecompileRequest {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(className, "className");
        canonicalMappings = Map.copyOf(canonicalMappings);
        libraries = List.copyOf(libraries);
    }
}
