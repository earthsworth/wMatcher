package org.earthsworth.wmatcher.core.model;

import java.util.List;
import java.util.Objects;

public record MethodModel(
        String name,
        String descriptor,
        String signature,
        int access,
        List<String> exceptions,
        List<String> annotations,
        int instructionCount,
        String instructionFingerprint,
        String structuralFingerprint) {
    public MethodModel {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        exceptions = List.copyOf(exceptions);
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(instructionFingerprint, "instructionFingerprint");
        Objects.requireNonNull(structuralFingerprint, "structuralFingerprint");
    }
}
