package org.earthsworth.wmatcher.core.service;

import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

public interface DiffEngine {
    DiffResult diff(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            ProgressListener progress,
            CancellationToken cancellation);
}
