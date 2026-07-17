package org.earthsworth.wmatcher.core.service;

import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

public interface MatchingEngine {
    MatchResult match(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MappingOverrides overrides,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation);
}
