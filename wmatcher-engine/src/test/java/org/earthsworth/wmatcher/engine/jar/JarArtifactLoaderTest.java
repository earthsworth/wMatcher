package org.earthsworth.wmatcher.engine.jar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarArtifactLoaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void readsClassesResourcesAndMultiReleaseOverlay() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("sample/Example.class", TestArtifacts.simpleClass("sample/Example", "base", 8, 10));
        entries.put("META-INF/versions/21/sample/Example.class",
                TestArtifacts.simpleClass("sample/Example", "modern", 21, 20));
        entries.put("config/app.properties", "name=wMatcher\n".getBytes(StandardCharsets.UTF_8));
        Path jar = TestArtifacts.writeJar(temporaryDirectory.resolve("sample.jar"), entries, true);

        var snapshot = new JarArtifactLoader().load(jar, ScanOptions.productionDefaults(),
                ProgressListener.NONE, CancellationToken.NONE);

        assertThat(snapshot.classes()).containsOnlyKeys("sample/Example");
        assertThat(snapshot.classes().get("sample/Example").entryName())
                .isEqualTo("META-INF/versions/21/sample/Example.class");
        assertThat(snapshot.classes().get("sample/Example").methods())
                .anyMatch(method -> method.name().equals("modern"));
        assertThat(snapshot.resources().get("config/app.properties").likelyText()).isTrue();
        assertThat(snapshot.sha256()).hasSize(64);
    }

    @Test
    void enforcesConfiguredFileLimit() throws Exception {
        Path jar = TestArtifacts.writeJar(temporaryDirectory.resolve("limited.jar"),
                Map.of("sample/A.class", TestArtifacts.simpleClass("sample/A", "run", 1, 1)), false);
        ScanOptions limits = new ScanOptions(21, 1, 10, 1024);

        assertThatThrownBy(() -> new JarArtifactLoader().load(
                jar, limits, ProgressListener.NONE, CancellationToken.NONE))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("1 GiB");
    }
}
