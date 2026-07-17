package org.earthsworth.wmatcher.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MatchResult(
        List<MatchDecision> confirmed,
        Map<EntityId, List<MatchDecision>> candidates,
        Map<EntityId, List<MatchDecision>> rankedCandidates,
        Set<EntityId> unmatchedLeft,
        Set<EntityId> unmatchedRight) {
    public MatchResult {
        confirmed = List.copyOf(confirmed);
        candidates = immutableCandidateMap(candidates);
        rankedCandidates = immutableCandidateMap(rankedCandidates);
        unmatchedLeft = Set.copyOf(unmatchedLeft);
        unmatchedRight = Set.copyOf(unmatchedRight);
    }

    public MatchResult(
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> candidates,
            Set<EntityId> unmatchedLeft,
            Set<EntityId> unmatchedRight) {
        this(confirmed, candidates, candidates, unmatchedLeft, unmatchedRight);
    }

    public Map<EntityId, EntityId> confirmedMappings() {
        Map<EntityId, EntityId> result = new LinkedHashMap<>();
        confirmed.forEach(match -> result.put(match.left(), match.right()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<EntityId, List<MatchDecision>> immutableCandidateMap(
            Map<EntityId, List<MatchDecision>> source) {
        Map<EntityId, List<MatchDecision>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }
}
