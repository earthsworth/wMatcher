package org.earthsworth.wmatcher.core.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ArtifactSnapshot(
        Path path,
        String sha256,
        long fileSize,
        long lastModified,
        int entryCount,
        int targetRelease,
        Map<String, String> manifest,
        Map<String, ClassModel> classes,
        Map<String, ResourceModel> resources) {
    public ArtifactSnapshot {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        manifest = immutableMap(manifest);
        classes = immutableMap(classes);
        resources = immutableMap(resources);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
