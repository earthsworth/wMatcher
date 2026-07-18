package org.earthsworth.wmatcher.core.model;

public record MappingNamespaces(String source, String target) {
    public MappingNamespaces {
        if (source == null || source.isBlank() || target == null || target.isBlank()
                || source.equals(target)) {
            throw new IllegalArgumentException("Mapping source and target namespaces must differ");
        }
    }
}
