package org.earthsworth.wmatcher.core.project;

import java.util.Map;
import java.util.Set;
import org.earthsworth.wmatcher.core.model.EntityId;

public record WMatcherProject(
        int formatVersion,
        ArtifactReference left,
        ArtifactReference right,
        int targetRelease,
        String matchingPolicy,
        Map<EntityId, EntityId> lockedMappings,
        Set<EntityId> confirmedRemoved,
        Set<EntityId> confirmedAdded,
        ProjectUiState uiState) {
    public static final int CURRENT_FORMAT = 1;

    public WMatcherProject {
        lockedMappings = Map.copyOf(lockedMappings);
        confirmedRemoved = Set.copyOf(confirmedRemoved);
        confirmedAdded = Set.copyOf(confirmedAdded);
        uiState = uiState == null ? ProjectUiState.empty() : uiState;
    }

    public WMatcherProject(
            int formatVersion,
            ArtifactReference left,
            ArtifactReference right,
            int targetRelease,
            String matchingPolicy,
            Map<EntityId, EntityId> lockedMappings,
            ProjectUiState uiState) {
        this(formatVersion, left, right, targetRelease, matchingPolicy, lockedMappings, Set.of(), Set.of(), uiState);
    }
}
