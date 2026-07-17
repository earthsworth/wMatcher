package org.earthsworth.wmatcher.core.model;

import java.util.Objects;

public record DecompiledSource(String className, String source, boolean fromCache) {
    public DecompiledSource {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(source, "source");
    }
}
