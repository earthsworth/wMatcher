package org.earthsworth.wmatcher.core.project;

import java.util.Map;
import java.util.Set;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.DetachedPair;
import org.earthsworth.wmatcher.core.model.ClassClassification;
import org.earthsworth.wmatcher.core.model.ClassPair;

public record WMatcherProject(
        int formatVersion,
        ArtifactReference left,
        ArtifactReference right,
        int targetRelease,
        String matchingPolicy,
        Map<EntityId, EntityId> lockedMappings,
        Set<EntityId> confirmedRemoved,
        Set<EntityId> confirmedAdded,
        Set<DetachedPair> detachedPairs,
        Map<ClassPair, ClassClassification> classifications,
        ProjectUiState uiState) {
    public static final int CURRENT_FORMAT = 1;

    public WMatcherProject {
        lockedMappings = Map.copyOf(lockedMappings);
        confirmedRemoved = Set.copyOf(confirmedRemoved);
        confirmedAdded = Set.copyOf(confirmedAdded);
        detachedPairs = Set.copyOf(detachedPairs);
        classifications = Map.copyOf(classifications);
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
        this(formatVersion, left, right, targetRelease, matchingPolicy, lockedMappings,
                Set.of(), Set.of(), Set.of(), Map.of(), uiState);
    }

    public WMatcherProject(
            int formatVersion,
            ArtifactReference left,
            ArtifactReference right,
            int targetRelease,
            String matchingPolicy,
            Map<EntityId, EntityId> lockedMappings,
            Set<EntityId> confirmedRemoved,
            Set<EntityId> confirmedAdded,
            ProjectUiState uiState) {
        this(formatVersion, left, right, targetRelease, matchingPolicy, lockedMappings,
                confirmedRemoved, confirmedAdded, Set.of(), Map.of(), uiState);
    }

    public WMatcherProject(
            int formatVersion,
            ArtifactReference left,
            ArtifactReference right,
            int targetRelease,
            String matchingPolicy,
            Map<EntityId, EntityId> lockedMappings,
            Set<EntityId> confirmedRemoved,
            Set<EntityId> confirmedAdded,
            Set<DetachedPair> detachedPairs,
            ProjectUiState uiState) {
        this(formatVersion, left, right, targetRelease, matchingPolicy, lockedMappings,
                confirmedRemoved, confirmedAdded, detachedPairs, Map.of(), uiState);
    }
}
