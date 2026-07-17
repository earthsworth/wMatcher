package org.earthsworth.wmatcher.engine.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultMatchingEngineTest {
    @TempDir Path temporaryDirectory;

    @Test
    void matchesRenamedClassFieldAndMethodDeterministically() throws Exception {
        Path oldJar = TestArtifacts.writeJar(temporaryDirectory.resolve("old.jar"),
                Map.of("old/Readable.class", TestArtifacts.simpleClass("old/Readable", "calculate", 7, 10)), false);
        Path newJar = TestArtifacts.writeJar(temporaryDirectory.resolve("new.jar"),
                Map.of("a/b.class", TestArtifacts.simpleClass("a/b", "x", 7, 99)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(oldJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(newJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        DefaultMatchingEngine engine = new DefaultMatchingEngine();

        var first = engine.match(left, right, MappingOverrides.EMPTY, MatchingPolicy.conservativeV1(),
                ProgressListener.NONE, CancellationToken.NONE);
        var second = engine.match(left, right, MappingOverrides.EMPTY, MatchingPolicy.conservativeV1(),
                ProgressListener.NONE, CancellationToken.NONE);

        assertThat(first.confirmedMappings()).containsEntry(EntityId.classId("old/Readable"), EntityId.classId("a/b"));
        assertThat(first.confirmed()).anyMatch(match -> match.left().name().equals("calculate")
                && match.right().name().equals("x"));
        assertThat(second.confirmed()).isEqualTo(first.confirmed());
    }

    @Test
    void retainsStableIdentityWhenMethodCodeChanges() throws Exception {
        Path oldJar = TestArtifacts.writeJar(temporaryDirectory.resolve("stable-old.jar"),
                Map.of("sample/Service.class", TestArtifacts.simpleClass("sample/Service", "value", 1, 1)), false);
        Path newJar = TestArtifacts.writeJar(temporaryDirectory.resolve("stable-new.jar"),
                Map.of("sample/Service.class", TestArtifacts.simpleClass("sample/Service", "value", 2, 20)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(oldJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(newJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);

        var result = new DefaultMatchingEngine().match(left, right, MappingOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.confirmedMappings()).containsEntry(
                EntityId.classId("sample/Service"), EntityId.classId("sample/Service"));
        assertThat(result.confirmed()).anyMatch(match -> match.left().name().equals("value")
                && match.right().name().equals("value"));
    }
}
