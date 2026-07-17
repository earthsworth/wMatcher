package org.earthsworth.wmatcher.core.service;

import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

public interface DiffEngine {
    default DiffResult diff(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            ProgressListener progress,
            CancellationToken cancellation) {
        return diff(left, right, matches, ComparisonOverrides.EMPTY, progress, cancellation);
    }

    DiffResult diff(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            ComparisonOverrides overrides,
            ProgressListener progress,
            CancellationToken cancellation);
}
