package org.earthsworth.wmatcher.core.service;

import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.core.model.EntityId;
import java.util.Set;

public interface MatchingEngine {
    default MatchingSession openSession(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation) {
        MatchingEngine engine = this;
        return new MatchingSession() {
            @Override
            public MatchResult match(
                    ComparisonOverrides overrides,
                    ProgressListener sessionProgress,
                    CancellationToken sessionCancellation) {
                return engine.match(left, right, overrides, policy, sessionProgress, sessionCancellation);
            }

            @Override
            public MatchResult rematch(
                    MatchResult previous,
                    ComparisonOverrides previousOverrides,
                    ComparisonOverrides overrides,
                    Set<EntityId> affectedEntities,
                    ProgressListener sessionProgress,
                    CancellationToken sessionCancellation) {
                return engine.rematch(left, right, previous, previousOverrides, overrides, affectedEntities,
                        policy, sessionProgress, sessionCancellation);
            }
        };
    }

    default MatchResult rematch(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult previous,
            ComparisonOverrides previousOverrides,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation) {
        return match(left, right, overrides, policy, progress, cancellation);
    }

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
