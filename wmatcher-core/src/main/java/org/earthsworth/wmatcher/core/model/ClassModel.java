package org.earthsworth.wmatcher.core.model;

import java.util.List;
import java.util.Objects;

public record ClassModel(
        String internalName,
        String entryName,
        int releaseVersion,
        int access,
        int classFileVersion,
        String signature,
        String superName,
        List<String> interfaces,
        List<String> annotations,
        List<FieldModel> fields,
        List<MethodModel> methods,
        String structuralFingerprint,
        String bytecodeFingerprint) {
    public ClassModel {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(entryName, "entryName");
        interfaces = List.copyOf(interfaces);
        annotations = List.copyOf(annotations);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        Objects.requireNonNull(structuralFingerprint, "structuralFingerprint");
        Objects.requireNonNull(bytecodeFingerprint, "bytecodeFingerprint");
    }

    public EntityId id() {
        return EntityId.classId(internalName);
    }
}
