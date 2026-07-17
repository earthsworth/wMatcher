package org.earthsworth.wmatcher.core.model;

import java.util.Objects;

/** Stable identity of a paired class, independent of its automatic match status. */
public record ClassPair(EntityId left, EntityId right) {
    public ClassPair {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.kind() != EntityKind.CLASS || right.kind() != EntityKind.CLASS) {
            throw new IllegalArgumentException("Class classifications require two class entities");
        }
    }

    public boolean involves(EntityId entity) {
        return left.equals(entity) || right.equals(entity);
    }
}
