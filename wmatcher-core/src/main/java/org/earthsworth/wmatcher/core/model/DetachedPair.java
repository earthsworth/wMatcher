package org.earthsworth.wmatcher.core.model;

import java.util.Objects;

/** A previously paired entity pair that the user explicitly split for re-evaluation. */
public record DetachedPair(EntityId left, EntityId right) {
    public DetachedPair {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.kind() != right.kind()) {
            throw new IllegalArgumentException("Detached pairs must contain the same entity kind");
        }
    }

    public boolean involves(EntityId entity) {
        return left.equals(entity) || right.equals(entity);
    }
}
