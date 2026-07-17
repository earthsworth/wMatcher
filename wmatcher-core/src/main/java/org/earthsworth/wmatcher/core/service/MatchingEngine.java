package org.earthsworth.wmatcher.core.service;

import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

public interface MatchingEngine {
    default MatchResult match(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MappingOverrides overrides,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation) {
        return match(left, right, new ComparisonOverrides(overrides.locked()), policy, progress, cancellation);
    }

    MatchResult match(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            ComparisonOverrides overrides,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation);
}
