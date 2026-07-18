package org.earthsworth.wmatcher.core.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.MappingFileFormat;
import org.earthsworth.wmatcher.core.model.MappingImportResult;
import org.earthsworth.wmatcher.core.model.MappingNamespaces;
import org.earthsworth.wmatcher.core.model.MatchResult;

public interface MappingService {
    MappingImportResult importMappings(
            MappingFileFormat format,
            Path path,
            MappingNamespaces namespaces,
            ArtifactSnapshot left,
            ArtifactSnapshot right) throws IOException;

    void exportMappings(Path path, MappingFileFormat format, MatchResult matches) throws IOException;

    default List<String> namespaces(Path path, MappingFileFormat format) throws IOException {
        return List.of();
    }

    Map<EntityId, EntityId> importTiny(Path path, ArtifactSnapshot left, ArtifactSnapshot right) throws IOException;

    Map<EntityId, EntityId> importProguard(
            Path leftMapping,
            Path rightMapping,
            ArtifactSnapshot left,
            ArtifactSnapshot right) throws IOException;

    void exportTiny(Path path, MatchResult matches) throws IOException;
}
