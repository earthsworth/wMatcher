package org.earthsworth.wmatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.match.DefaultMatchingEngine;
import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("performance")
class PerformanceAcceptanceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void indexesAndMatchesConfiguredLargeFixtureWithinTwoMinutes() throws Exception {
        int classCount = Integer.getInteger("wmatcher.performance.classes", 50_000);
        Map<String, byte[]> leftEntries = new LinkedHashMap<>();
        Map<String, byte[]> rightEntries = new LinkedHashMap<>();
        for (int index = 0; index < classCount; index++) {
            String leftName = "old/C" + index;
            String rightName = "new/X" + index;
            int value = index % 997;
            leftEntries.put(leftName + ".class", TestArtifacts.simpleClass(leftName, "m" + index, value, 1));
            rightEntries.put(rightName + ".class", TestArtifacts.simpleClass(rightName, "x" + index, value, 2));
        }
        Path leftJar = TestArtifacts.writeJar(temporaryDirectory.resolve("large-left.jar"), leftEntries, false);
        Path rightJar = TestArtifacts.writeJar(temporaryDirectory.resolve("large-right.jar"), rightEntries, false);
        Instant started = Instant.now();
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(leftJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(rightJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var matches = new DefaultMatchingEngine().match(left, right, MappingOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        assertThat(left.classes()).hasSize(classCount);
        assertThat(right.classes()).hasSize(classCount);
        assertThat(matches.confirmed().size() + matches.unmatchedLeft().size()).isGreaterThanOrEqualTo(classCount);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(120));
    }
}
