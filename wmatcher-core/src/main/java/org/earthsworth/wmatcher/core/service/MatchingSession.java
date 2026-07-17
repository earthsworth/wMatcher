package org.earthsworth.wmatcher.core.service;

import java.util.Set;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchingUpdate;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

/** Reusable candidate analysis for one pair of artifacts. */
public interface MatchingSession extends AutoCloseable {
    MatchResult match(
            ComparisonOverrides overrides,
            ProgressListener progress,
            CancellationToken cancellation);

    MatchResult rematch(
            MatchResult previous,
            ComparisonOverrides previousOverrides,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            ProgressListener progress,
            CancellationToken cancellation);

    default MatchingUpdate rematchUpdate(
            MatchResult previous,
            ComparisonOverrides previousOverrides,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            ProgressListener progress,
            CancellationToken cancellation) {
        return new MatchingUpdate(rematch(previous, previousOverrides, overrides, affectedEntities,
                progress, cancellation), affectedEntities, false);
    }

    default long maximumCacheWeight() {
        return 512L * 1024 * 1024;
    }

    default long currentCacheWeight() {
        return 0;
    }

    @Override
    default void close() { }
}
