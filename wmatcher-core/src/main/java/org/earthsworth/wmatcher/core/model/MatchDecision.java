package org.earthsworth.wmatcher.core.model;

import java.util.Objects;

public record MatchDecision(
        EntityId left,
        EntityId right,
        MatchStatus status,
        ScoreBreakdown score) {
    public MatchDecision {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(score, "score");
        if (left.kind() != right.kind()) {
            throw new IllegalArgumentException("Matched entities must have the same kind");
        }
    }
}
