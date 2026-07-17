package org.earthsworth.wmatcher.core.project;

import java.util.Map;
import org.earthsworth.wmatcher.core.model.EntityId;

public record WMatcherProject(
        int formatVersion,
        ArtifactReference left,
        ArtifactReference right,
        int targetRelease,
        String matchingPolicy,
        Map<EntityId, EntityId> lockedMappings,
        ProjectUiState uiState) {
    public static final int CURRENT_FORMAT = 1;

    public WMatcherProject {
        lockedMappings = Map.copyOf(lockedMappings);
        uiState = uiState == null ? ProjectUiState.empty() : uiState;
    }
}
