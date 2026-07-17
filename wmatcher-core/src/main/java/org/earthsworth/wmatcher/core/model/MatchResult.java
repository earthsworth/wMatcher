package org.earthsworth.wmatcher.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MatchResult(
        List<MatchDecision> confirmed,
        Map<EntityId, List<MatchDecision>> candidates,
        Set<EntityId> unmatchedLeft,
        Set<EntityId> unmatchedRight) {
    public MatchResult {
        confirmed = List.copyOf(confirmed);
        Map<EntityId, List<MatchDecision>> candidateCopy = new LinkedHashMap<>();
        candidates.forEach((key, value) -> candidateCopy.put(key, List.copyOf(value)));
        candidates = Collections.unmodifiableMap(candidateCopy);
        unmatchedLeft = Set.copyOf(unmatchedLeft);
        unmatchedRight = Set.copyOf(unmatchedRight);
    }

    public Map<EntityId, EntityId> confirmedMappings() {
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        confirmed.forEach(match -> result.put(match.left(), match.right()));
        return Collections.unmodifiableMap(result);
    }
}
