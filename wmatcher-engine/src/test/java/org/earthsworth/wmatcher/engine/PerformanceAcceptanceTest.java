package org.earthsworth.wmatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.EntityId;
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
        Path leftJar = writeLargeJar(temporaryDirectory.resolve("large-left.jar"), classCount, true);
        Path rightJar = writeLargeJar(temporaryDirectory.resolve("large-right.jar"), classCount, false);
        Instant started = Instant.now();
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(leftJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(rightJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        DefaultMatchingEngine engine = new DefaultMatchingEngine();
        var session = engine.openSession(left, right, MatchingPolicy.conservativeV1(),
                ProgressListener.NONE, CancellationToken.NONE);
        var matches = session.match(new ComparisonOverrides(MappingOverrides.EMPTY.locked()),
                ProgressListener.NONE, CancellationToken.NONE);

        assertThat(left.classes()).hasSize(classCount);
        assertThat(right.classes()).hasSize(classCount);
        assertThat(matches.confirmed().size() + matches.unmatchedLeft().size()).isGreaterThanOrEqualTo(classCount);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(120));

        EntityId oldClass = EntityId.classId("old/C0");
        EntityId newClass = EntityId.classId("new/X0");
        ComparisonOverrides automatic = ComparisonOverrides.EMPTY;
        ComparisonOverrides manual = new ComparisonOverrides(Map.of(oldClass, newClass));
        List<Long> samples = new ArrayList<>();
        var current = matches;
        ComparisonOverrides currentOverrides = automatic;
        for (int iteration = 0; iteration < 14; iteration++) {
            ComparisonOverrides target = iteration % 2 == 0 ? manual : automatic;
            long operationStarted = System.nanoTime();
            var update = session.rematchUpdate(current, currentOverrides, target, Set.of(oldClass, newClass),
                    ProgressListener.NONE, CancellationToken.NONE);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - operationStarted);
            if (iteration >= 2) samples.add(elapsed);
            current = update.result();
            currentOverrides = target;
        }
        samples.sort(Comparator.naturalOrder());
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        assertThat(p95).as("single-class incremental rematch p95").isLessThan(250L);
        session.close();
    }

    private static Path writeLargeJar(Path path, int classCount, boolean left) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (int index = 0; index < classCount; index++) {
                String name = left ? "old/C" + index : "new/X" + index;
                String method = left ? "m" + index : "x" + index;
                int value = index;
                output.putNextEntry(new JarEntry(name + ".class"));
                output.write(TestArtifacts.simpleClass(name, method, value, left ? 1 : 2));
                output.closeEntry();
            }
        }
        return path;
    }
}
