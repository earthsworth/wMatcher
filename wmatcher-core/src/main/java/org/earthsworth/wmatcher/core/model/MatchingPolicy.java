package org.earthsworth.wmatcher.core.model;

public record MatchingPolicy(
        String version,
        double automaticThreshold,
        double candidateThreshold,
        double minimumMargin,
        int maxCandidates) {
    public MatchingPolicy {
        if (candidateThreshold < 0 || automaticThreshold > 1
                || candidateThreshold > automaticThreshold || minimumMargin < 0 || maxCandidates < 1) {
            throw new IllegalArgumentException("Invalid matching policy");
        }
    }

    public static MatchingPolicy conservativeV1() {
        return new MatchingPolicy("conservative-v1", 0.90, 0.70, 0.08, 5);
    }
}
