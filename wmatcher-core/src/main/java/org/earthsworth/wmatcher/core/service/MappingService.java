package org.earthsworth.wmatcher.core.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.MatchResult;

public interface MappingService {
    Map<EntityId, EntityId> importTiny(Path path, ArtifactSnapshot left, ArtifactSnapshot right) throws IOException;

    Map<EntityId, EntityId> importProguard(
            Path leftMapping,
            Path rightMapping,
            ArtifactSnapshot left,
            ArtifactSnapshot right) throws IOException;

    void exportTiny(Path path, MatchResult matches) throws IOException;
}
