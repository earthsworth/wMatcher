package org.earthsworth.wmatcher.engine.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.project.ArtifactReference;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.earthsworth.wmatcher.core.project.WMatcherProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JacksonProjectRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesAndLoadsVersionedProjectAtomically() throws Exception {
        WMatcherProject project = new WMatcherProject(1,
                new ArtifactReference("old.jar", "a", 1, 2),
                new ArtifactReference("new.jar", "b", 3, 4),
                21,
                "conservative-v1",
                Map.of(EntityId.classId("old/A"), EntityId.classId("new/B")),
                new ProjectUiState("needle", Set.of("CHANGED"), "entity:L:CLASS:old/A",
                        Set.of("root:classes", "package:classes:old"), 120, 285));
        Path path = temporaryDirectory.resolve("sample.wmatch");
        JacksonProjectRepository repository = new JacksonProjectRepository();

        repository.save(path, project);
        WMatcherProject restored = repository.load(path);

        assertThat(restored).isEqualTo(project);
        assertThat(path).exists();
        assertThat(temporaryDirectory).isDirectoryContaining(file -> file.getFileName().toString().equals("sample.wmatch"));
    }

    @Test
    void loadsLegacyProjectWithoutExtendedUiStateFields() throws Exception {
        Path path = temporaryDirectory.resolve("legacy.wmatch");
        java.nio.file.Files.writeString(path, """
                {
                  "formatVersion" : 1,
                  "left" : { "path" : "old.jar", "sha256" : "a", "size" : 1, "lastModified" : 2 },
                  "right" : { "path" : "new.jar", "sha256" : "b", "size" : 3, "lastModified" : 4 },
                  "targetRelease" : 21,
                  "matchingPolicy" : "conservative-v1",
                  "mappings" : [ ],
                  "uiState" : { "search" : "legacy", "filters" : [ "CHANGED" ], "selectedKey" : "old-key" }
                }
                """);

        WMatcherProject restored = new JacksonProjectRepository().load(path);

        assertThat(restored.uiState().search()).isEqualTo("legacy");
        assertThat(restored.uiState().expandedTreeKeys()).contains("root:classes", "root:resources");
        assertThat(restored.uiState().navigationDividerLocation()).isEqualTo(340);
    }

    @Test
    void preservesAnExplicitlyCollapsedTree() throws Exception {
        WMatcherProject project = new WMatcherProject(1,
                new ArtifactReference("old.jar", "a", 1, 2),
                new ArtifactReference("new.jar", "b", 3, 4),
                21,
                "conservative-v1",
                Map.of(),
                new ProjectUiState("", Set.of(), "", Set.of(), 0, 340));
        Path path = temporaryDirectory.resolve("collapsed.wmatch");
        JacksonProjectRepository repository = new JacksonProjectRepository();

        repository.save(path, project);

        assertThat(repository.load(path).uiState().expandedTreeKeys()).isEmpty();
    }
}
