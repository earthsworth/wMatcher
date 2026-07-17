package org.earthsworth.wmatcher.core.model;

import java.util.List;
import java.util.Objects;

public record FieldModel(
        String name,
        String descriptor,
        String signature,
        int access,
        String constantValue,
        List<String> annotations,
        String fingerprint) {
    public FieldModel {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
