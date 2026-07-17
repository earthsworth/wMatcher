package org.earthsworth.wmatcher.core.service;

import java.util.Set;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.DiffUpdate;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

/** Reusable, indexed difference calculation for one pair of artifacts. */
public interface DiffSession extends AutoCloseable {
    DiffResult diff(
            MatchResult matches,
            ComparisonOverrides overrides,
            ProgressListener progress,
            CancellationToken cancellation);

    DiffResult rediff(
            MatchResult matches,
            DiffResult previous,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            ProgressListener progress,
            CancellationToken cancellation);

    default DiffUpdate rediffUpdate(
            MatchResult matches,
            DiffResult previous,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            ProgressListener progress,
            CancellationToken cancellation) {
        return new DiffUpdate(rediff(matches, previous, overrides, affectedEntities, progress, cancellation),
                affectedEntities, false);
    }

    @Override
    default void close() { }
}
