package org.earthsworth.wmatcher.engine.project;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.project.ArtifactReference;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.earthsworth.wmatcher.core.project.WMatcherProject;
import org.earthsworth.wmatcher.core.service.ProjectRepository;

public final class JacksonProjectRepository implements ProjectRepository {
    private final ObjectMapper mapper;

    public JacksonProjectRepository() {
        mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public WMatcherProject load(Path path) throws IOException {
        ProjectFile file = mapper.readValue(path.toFile(), ProjectFile.class);
        if (file.formatVersion() != WMatcherProject.CURRENT_FORMAT) {
            throw new IOException("Unsupported .wmatch format version: " + file.formatVersion());
        }
        Map<EntityId, EntityId> mappings = file.mappings().stream().collect(java.util.stream.Collectors.toMap(
                pair -> pair.left().toEntityId(),
                pair -> pair.right().toEntityId(),
                (first, ignored) -> first,
                java.util.LinkedHashMap::new));
        return new WMatcherProject(
                file.formatVersion(),
                file.left(),
                file.right(),
                file.targetRelease(),
                file.matchingPolicy(),
                mappings,
                file.uiState());
    }

    @Override
    public void save(Path path, WMatcherProject project) throws IOException {
        Path destination = path.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        List<MappingPairFile> mappings = project.lockedMappings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                .map(entry -> new MappingPairFile(EntityFile.from(entry.getKey()), EntityFile.from(entry.getValue())))
                .toList();
        ProjectFile file = new ProjectFile(
                project.formatVersion(),
                project.left(),
                project.right(),
                project.targetRelease(),
                project.matchingPolicy(),
                mappings,
                project.uiState());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            mapper.writeValue(temporary.toFile(), file);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record ProjectFile(
            int formatVersion,
            ArtifactReference left,
            ArtifactReference right,
            int targetRelease,
            String matchingPolicy,
            List<MappingPairFile> mappings,
            ProjectUiState uiState) {
        public ProjectFile {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }
    }

    public record MappingPairFile(EntityFile left, EntityFile right) { }

    public record EntityFile(String kind, String owner, String name, String descriptor) {
        static EntityFile from(EntityId id) {
            return new EntityFile(id.kind().name(), id.owner(), id.name(), id.descriptor());
        }

        EntityId toEntityId() {
            return new EntityId(EntityKind.valueOf(kind), owner, name, descriptor);
        }
    }
}
