package org.earthsworth.wmatcher.core.model;

import java.util.Objects;

public record ResourceModel(String path, long size, String sha256, boolean likelyText) {
    public ResourceModel {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
    }

    public EntityId id() {
        return EntityId.resourceId(path);
    }
}
