package org.earthsworth.wmatcher.engine.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.match.DefaultMatchingEngine;
import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingIoServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exportsAndImportsConfirmedTinyV2Mappings() throws Exception {
        Path leftJar = TestArtifacts.writeJar(temporaryDirectory.resolve("left.jar"),
                Map.of("old/A.class", TestArtifacts.simpleClass("old/A", "read", 3, 1)), false);
        Path rightJar = TestArtifacts.writeJar(temporaryDirectory.resolve("right.jar"),
                Map.of("x/Y.class", TestArtifacts.simpleClass("x/Y", "a", 3, 1)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(leftJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(rightJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var matches = new DefaultMatchingEngine().match(left, right, MappingOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);
        MappingIoService service = new MappingIoService();
        Path tiny = temporaryDirectory.resolve("mapping.tiny");

        service.exportTiny(tiny, matches);
        var imported = service.importTiny(tiny, left, right);

        assertThat(imported).containsEntry(EntityId.classId("old/A"), EntityId.classId("x/Y"));
        assertThat(imported).containsAllEntriesOf(matches.confirmedMappings());
    }

    @Test
    void joinsPairedProguardMappingsThroughOriginalNames() throws Exception {
        Path leftJar = TestArtifacts.writeJar(temporaryDirectory.resolve("proguard-left.jar"),
                Map.of("old/Readable.class", TestArtifacts.simpleClass("old/Readable", "calculate", 3, 1)), false);
        Path rightJar = TestArtifacts.writeJar(temporaryDirectory.resolve("proguard-right.jar"),
                Map.of("a/b.class", TestArtifacts.simpleClass("a/b", "x", 3, 1)), false);
        Path leftMap = temporaryDirectory.resolve("old-map.txt");
        Path rightMap = temporaryDirectory.resolve("new-map.txt");
        Files.writeString(leftMap, "example.Service -> old.Readable:\n"
                + "    int value -> value\n"
                + "    int calculate() -> calculate\n");
        Files.writeString(rightMap, "example.Service -> a.b:\n"
                + "    int value -> value\n"
                + "    int calculate() -> x\n");
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(leftJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(rightJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);

        var imported = new MappingIoService().importProguard(leftMap, rightMap, left, right);

        assertThat(imported).containsEntry(EntityId.classId("old/Readable"), EntityId.classId("a/b"));
        assertThat(imported).anySatisfy((oldId, newId) -> {
            assertThat(oldId.name()).isEqualTo("calculate");
            assertThat(newId.name()).isEqualTo("x");
        });
    }
}
