package org.earthsworth.wmatcher.core.service;

import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.core.model.EntityId;
import java.util.Set;

public interface DiffEngine {
    default DiffSession openSession(ArtifactSnapshot left, ArtifactSnapshot right) {
        DiffEngine engine = this;
        return new DiffSession() {
            @Override
            public DiffResult diff(
                    MatchResult matches,
                    ComparisonOverrides overrides,
                    ProgressListener progress,
                    CancellationToken cancellation) {
                return engine.diff(left, right, matches, overrides, progress, cancellation);
            }

            @Override
            public DiffResult rediff(
                    MatchResult matches,
                    DiffResult previous,
                    ComparisonOverrides overrides,
                    Set<EntityId> affectedEntities,
                    ProgressListener progress,
                    CancellationToken cancellation) {
                return engine.rediff(left, right, matches, previous, overrides, affectedEntities,
                        progress, cancellation);
            }
        };
    }

    default DiffResult rediff(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            DiffResult previous,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            ProgressListener progress,
            CancellationToken cancellation) {
        return diff(left, right, matches, overrides, progress, cancellation);
    }

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
