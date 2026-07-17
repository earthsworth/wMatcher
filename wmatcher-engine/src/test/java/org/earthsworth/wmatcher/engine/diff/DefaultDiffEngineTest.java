package org.earthsworth.wmatcher.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.earthsworth.wmatcher.core.model.ChangeKind;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DetachedPair;
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

class DefaultDiffEngineTest {
    @TempDir Path temporaryDirectory;

    @Test
    void identifiesUniqueMovedResource() throws Exception {
        byte[] resource = "same content".getBytes(StandardCharsets.UTF_8);
        Path leftJar = TestArtifacts.writeJar(temporaryDirectory.resolve("left.jar"),
                new LinkedHashMap<>(Map.of("old/name.txt", resource)), false);
        Path rightJar = TestArtifacts.writeJar(temporaryDirectory.resolve("right.jar"),
                new LinkedHashMap<>(Map.of("new/name.txt", resource)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(leftJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(rightJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var matches = new DefaultMatchingEngine().match(left, right, MappingOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        var result = new DefaultDiffEngine().diff(left, right, matches, ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.nodes()).anyMatch(node -> node.changes().contains(ChangeKind.MOVED)
                && node.displayName().contains("old/name.txt") && node.displayName().contains("new/name.txt"));
    }

    @Test
    void detachedMovedResourceIsSplitIntoOldAndNewSingleSidedNodes() throws Exception {
        byte[] resource = "same content".getBytes(StandardCharsets.UTF_8);
        Path leftJar = TestArtifacts.writeJar(temporaryDirectory.resolve("detached-left.jar"),
                new LinkedHashMap<>(Map.of("old/name.txt", resource)), false);
        Path rightJar = TestArtifacts.writeJar(temporaryDirectory.resolve("detached-right.jar"),
                new LinkedHashMap<>(Map.of("new/name.txt", resource)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(leftJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(rightJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        ComparisonOverrides overrides = new ComparisonOverrides(Map.of(), Set.of(), Set.of(), Set.of(
                new DetachedPair(EntityId.resourceId("old/name.txt"), EntityId.resourceId("new/name.txt"))));
        var matches = new DefaultMatchingEngine().match(left, right, overrides,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        var result = new DefaultDiffEngine().diff(
                left, right, matches, overrides, ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.nodes().stream().filter(node -> node.kind()
                == org.earthsworth.wmatcher.core.model.EntityKind.RESOURCE)
                .filter(node -> node.displayName().endsWith("name.txt"))).hasSize(2);
        assertThat(result.nodes()).anyMatch(node -> node.left() != null && node.right() == null
                && node.changes().contains(ChangeKind.REMOVED));
        assertThat(result.nodes()).anyMatch(node -> node.left() == null && node.right() != null
                && node.changes().contains(ChangeKind.ADDED));
    }
}
