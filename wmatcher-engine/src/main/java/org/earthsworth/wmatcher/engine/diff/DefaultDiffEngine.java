package org.earthsworth.wmatcher.engine.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ChangeKind;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.DetachedPair;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.core.model.ResourceModel;
import org.earthsworth.wmatcher.core.model.ResolutionStatus;
import org.earthsworth.wmatcher.core.service.DiffEngine;
import org.earthsworth.wmatcher.core.service.DiffSession;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;

public final class DefaultDiffEngine implements DiffEngine {
    @Override
    public DiffSession openSession(ArtifactSnapshot left, ArtifactSnapshot right) {
        return new DiffSession() {
            private boolean closed;

            @Override
            public DiffResult diff(
                    MatchResult matches,
                    ComparisonOverrides overrides,
                    ProgressListener progress,
                    CancellationToken cancellation) {
                ensureOpen();
                return DefaultDiffEngine.this.diff(left, right, matches, overrides, progress, cancellation);
            }

            @Override
            public DiffResult rediff(
                    MatchResult matches,
                    DiffResult previous,
                    ComparisonOverrides overrides,
                    Set<EntityId> affectedEntities,
                    ProgressListener progress,
                    CancellationToken cancellation) {
                ensureOpen();
                return DefaultDiffEngine.this.rediff(
                        left, right, matches, previous, overrides, affectedEntities, progress, cancellation);
            }

            @Override public void close() { closed = true; }

            private void ensureOpen() {
                if (closed) throw new IllegalStateException("Diff session is closed");
            }
        };
    }

    @Override
    public DiffResult rediff(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            DiffResult previous,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            ProgressListener progress,
            CancellationToken cancellation) {
        if (affectedEntities.isEmpty()) return previous;
        boolean resourcesAffected = affectedEntities.stream().anyMatch(id -> id.kind() == EntityKind.RESOURCE);
        Set<String> affectedLeftClasses = affectedEntities.stream()
                .filter(id -> id.kind() != EntityKind.RESOURCE)
                .map(id -> id.kind() == EntityKind.CLASS ? id.name() : id.owner())
                .filter(left.classes()::containsKey)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> affectedRightClasses = affectedEntities.stream()
                .filter(id -> id.kind() != EntityKind.RESOURCE)
                .map(id -> id.kind() == EntityKind.CLASS ? id.name() : id.owner())
                .filter(right.classes()::containsKey)
                .collect(Collectors.toCollection(HashSet::new));
        matches.confirmed().stream().filter(match -> match.left().kind() == EntityKind.CLASS).forEach(match -> {
            if (affectedLeftClasses.contains(match.left().name())
                    || affectedRightClasses.contains(match.right().name())) {
                affectedLeftClasses.add(match.left().name());
                affectedRightClasses.add(match.right().name());
            }
        });

        List<DiffNode> nodes = previous.nodes().stream()
                .filter(node -> !resourcesAffected || node.kind() != EntityKind.RESOURCE)
                .filter(node -> node.kind() == EntityKind.RESOURCE
                        || !touchesClasses(node, affectedLeftClasses, affectedRightClasses))
                .collect(Collectors.toCollection(ArrayList::new));

        Map<String, String> matchedClasses = matches.confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .collect(Collectors.toMap(match -> match.left().name(), match -> match.right().name()));
        Set<EntityId> matchedLeft = matches.confirmed().stream().map(MatchDecision::left).collect(Collectors.toSet());
        Set<EntityId> matchedRight = matches.confirmed().stream().map(MatchDecision::right).collect(Collectors.toSet());
        Set<EntityId> candidateLeft = matches.candidates().keySet();
        Set<EntityId> candidateRight = matches.candidates().values().stream()
                .flatMap(List::stream).map(MatchDecision::right).collect(Collectors.toSet());

        for (MatchDecision match : matches.confirmed()) {
            if (!touchesClasses(match, affectedLeftClasses, affectedRightClasses)) continue;
            cancellation.throwIfCancelled();
            DiffNode node = switch (match.left().kind()) {
                case CLASS -> classNode(left, right, match);
                case FIELD -> fieldNode(left, right, match);
                case METHOD -> methodNode(left, right, match);
                case RESOURCE -> null;
            };
            if (node != null) nodes.add(node);
        }
        left.classes().values().stream()
                .filter(model -> affectedLeftClasses.contains(model.internalName()))
                .filter(model -> !matchedLeft.contains(model.id()))
                .forEach(model -> nodes.add(new DiffNode("class:L:" + model.internalName(), model.internalName(),
                        EntityKind.CLASS, model.id(), null,
                        Set.of(candidateLeft.contains(model.id()) ? ChangeKind.UNRESOLVED : ChangeKind.REMOVED),
                        resolution(model.id(), true, overrides))));
        right.classes().values().stream()
                .filter(model -> affectedRightClasses.contains(model.internalName()))
                .filter(model -> !matchedRight.contains(model.id()))
                .forEach(model -> nodes.add(new DiffNode("class:R:" + model.internalName(), model.internalName(),
                        EntityKind.CLASS, null, model.id(),
                        Set.of(candidateRight.contains(model.id()) ? ChangeKind.UNRESOLVED : ChangeKind.ADDED),
                        resolution(model.id(), false, overrides))));

        List<DiffNode> members = new ArrayList<>();
        addUnmatchedMembers(left, matches.unmatchedLeft(), matchedClasses.keySet(), candidateLeft, true,
                overrides, members);
        addUnmatchedMembers(right, matches.unmatchedRight(), new HashSet<>(matchedClasses.values()), candidateRight,
                false, overrides, members);
        members.stream().filter(node -> touchesClasses(node, affectedLeftClasses, affectedRightClasses))
                .forEach(nodes::add);
        if (resourcesAffected) addResourceNodes(left.resources(), right.resources(), overrides, nodes, cancellation);
        nodes.sort(Comparator.comparing(DiffNode::kind).thenComparing(DiffNode::displayName));
        return result(nodes);
    }

    private static boolean touchesClasses(
            DiffNode node, Set<String> leftClasses, Set<String> rightClasses) {
        return className(node.left()) != null && leftClasses.contains(className(node.left()))
                || className(node.right()) != null && rightClasses.contains(className(node.right()));
    }

    private static boolean touchesClasses(
            MatchDecision match, Set<String> leftClasses, Set<String> rightClasses) {
        return className(match.left()) != null && leftClasses.contains(className(match.left()))
                || className(match.right()) != null && rightClasses.contains(className(match.right()));
    }

    private static String className(EntityId id) {
        if (id == null || id.kind() == EntityKind.RESOURCE) return null;
        return id.kind() == EntityKind.CLASS ? id.name() : id.owner();
    }

    @Override
    public DiffResult diff(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult matches,
            ComparisonOverrides overrides,
            ProgressListener progress,
            CancellationToken cancellation) {
        List<DiffNode> nodes = new ArrayList<>();
        Map<String, String> matchedClasses = matches.confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .collect(Collectors.toMap(match -> match.left().name(), match -> match.right().name()));
        Set<EntityId> matchedLeft = matches.confirmed().stream().map(MatchDecision::left).collect(Collectors.toSet());
        Set<EntityId> matchedRight = matches.confirmed().stream().map(MatchDecision::right).collect(Collectors.toSet());
        Set<EntityId> candidateLeft = matches.candidates().keySet();
        Set<EntityId> candidateRight = matches.candidates().values().stream()
                .flatMap(List::stream).map(MatchDecision::right).collect(Collectors.toSet());

        long total = matches.confirmed().size() + left.resources().size() + right.resources().size();
        long completed = 0;
        for (MatchDecision match : matches.confirmed()) {
            cancellation.throwIfCancelled();
            DiffNode node = switch (match.left().kind()) {
                case CLASS -> classNode(left, right, match);
                case FIELD -> fieldNode(left, right, match);
                case METHOD -> methodNode(left, right, match);
                case RESOURCE -> null;
            };
            if (node != null) {
                nodes.add(node);
            }
            progress.onProgress("Computing differences", ++completed, total);
        }

        for (ClassModel model : left.classes().values()) {
            cancellation.throwIfCancelled();
            if (!matchedLeft.contains(model.id())) {
                nodes.add(new DiffNode("class:L:" + model.internalName(), model.internalName(), EntityKind.CLASS,
                        model.id(), null, Set.of(candidateLeft.contains(model.id())
                                ? ChangeKind.UNRESOLVED : ChangeKind.REMOVED), resolution(model.id(), true, overrides)));
            }
        }
        for (ClassModel model : right.classes().values()) {
            cancellation.throwIfCancelled();
            if (!matchedRight.contains(model.id())) {
                nodes.add(new DiffNode("class:R:" + model.internalName(), model.internalName(), EntityKind.CLASS,
                        null, model.id(), Set.of(candidateRight.contains(model.id())
                                ? ChangeKind.UNRESOLVED : ChangeKind.ADDED), resolution(model.id(), false, overrides)));
            }
        }

        addUnmatchedMembers(left, matches.unmatchedLeft(), matchedClasses.keySet(), candidateLeft, true, overrides, nodes);
        addUnmatchedMembers(right, matches.unmatchedRight(), new HashSet<>(matchedClasses.values()), candidateRight,
                false, overrides, nodes);
        addResourceNodes(left.resources(), right.resources(), overrides, nodes, cancellation);

        nodes.sort(Comparator.comparing(DiffNode::kind).thenComparing(DiffNode::displayName));
        return result(nodes);
    }

    private static DiffResult result(List<DiffNode> nodes) {
        Map<ChangeKind, Long> counts = new EnumMap<>(ChangeKind.class);
        for (DiffNode node : nodes) node.changes().forEach(change -> counts.merge(change, 1L, Long::sum));
        return new DiffResult(nodes, counts);
    }

    private static DiffNode classNode(ArtifactSnapshot left, ArtifactSnapshot right, MatchDecision match) {
        ClassModel leftClass = left.classes().get(match.left().name());
        ClassModel rightClass = right.classes().get(match.right().name());
        Set<ChangeKind> changes = new HashSet<>();
        if (!leftClass.internalName().equals(rightClass.internalName())) {
            changes.add(ChangeKind.RENAMED);
        }
        if (!leftClass.structuralFingerprint().equals(rightClass.structuralFingerprint())) {
            changes.add(ChangeKind.STRUCTURE);
        }
        if (!leftClass.bytecodeFingerprint().equals(rightClass.bytecodeFingerprint())) {
            changes.add(ChangeKind.CODE);
        }
        String display = leftClass.internalName().equals(rightClass.internalName())
                ? leftClass.internalName()
                : leftClass.internalName() + " → " + rightClass.internalName();
        return new DiffNode("class:" + leftClass.internalName(), display, EntityKind.CLASS,
                match.left(), match.right(), changes);
    }

    private static DiffNode fieldNode(ArtifactSnapshot left, ArtifactSnapshot right, MatchDecision match) {
        FieldModel leftField = findField(left, match.left());
        FieldModel rightField = findField(right, match.right());
        Set<ChangeKind> changes = new HashSet<>();
        if (!leftField.name().equals(rightField.name())) {
            changes.add(ChangeKind.RENAMED);
        }
        if (!leftField.fingerprint().equals(rightField.fingerprint())
                || !leftField.descriptor().equals(rightField.descriptor())) {
            changes.add(ChangeKind.STRUCTURE);
        }
        return new DiffNode("field:" + match.left().externalName(), display(match), EntityKind.FIELD,
                match.left(), match.right(), changes);
    }

    private static DiffNode methodNode(ArtifactSnapshot left, ArtifactSnapshot right, MatchDecision match) {
        MethodModel leftMethod = findMethod(left, match.left());
        MethodModel rightMethod = findMethod(right, match.right());
        Set<ChangeKind> changes = new HashSet<>();
        if (!leftMethod.name().equals(rightMethod.name())) {
            changes.add(ChangeKind.RENAMED);
        }
        if (!leftMethod.structuralFingerprint().equals(rightMethod.structuralFingerprint())
                || !leftMethod.descriptor().equals(rightMethod.descriptor())) {
            changes.add(ChangeKind.STRUCTURE);
        }
        if (!leftMethod.instructionFingerprint().equals(rightMethod.instructionFingerprint())) {
            changes.add(ChangeKind.CODE);
        }
        return new DiffNode("method:" + match.left().externalName(), display(match), EntityKind.METHOD,
                match.left(), match.right(), changes);
    }

    private static void addUnmatchedMembers(
            ArtifactSnapshot artifact,
            Set<EntityId> unmatched,
            Set<String> matchedOwners,
            Set<EntityId> candidates,
            boolean left,
            ComparisonOverrides overrides,
            List<DiffNode> nodes) {
        for (EntityId id : unmatched) {
            if ((id.kind() == EntityKind.FIELD || id.kind() == EntityKind.METHOD) && matchedOwners.contains(id.owner())) {
                ChangeKind change = candidates.contains(id)
                        ? ChangeKind.UNRESOLVED : left ? ChangeKind.REMOVED : ChangeKind.ADDED;
                nodes.add(new DiffNode(
                        (left ? "L:" : "R:") + id.externalName(),
                        id.externalName(),
                        id.kind(),
                        left ? id : null,
                        left ? null : id,
                        Set.of(change),
                        resolution(id, left, overrides)));
            }
        }
    }

    private static void addResourceNodes(
            Map<String, ResourceModel> left,
            Map<String, ResourceModel> right,
            ComparisonOverrides overrides,
            List<DiffNode> nodes,
            CancellationToken cancellation) {
        Set<String> common = new HashSet<>(left.keySet());
        common.retainAll(right.keySet());
        Set<EntityId> detachedLeft = overrides.detachedPairs().stream()
                .filter(pair -> pair.left().kind() == EntityKind.RESOURCE)
                .map(DetachedPair::left).collect(Collectors.toSet());
        Set<EntityId> detachedRight = overrides.detachedPairs().stream()
                .filter(pair -> pair.right().kind() == EntityKind.RESOURCE)
                .map(DetachedPair::right).collect(Collectors.toSet());
        common.removeIf(path -> detachedLeft.contains(left.get(path).id())
                || detachedRight.contains(right.get(path).id()));
        for (String path : common) {
            cancellation.throwIfCancelled();
            ResourceModel leftResource = left.get(path);
            ResourceModel rightResource = right.get(path);
            Set<ChangeKind> changes = leftResource.sha256().equals(rightResource.sha256())
                    ? Set.of() : Set.of(ChangeKind.RESOURCE);
            nodes.add(new DiffNode("resource:" + path, path, EntityKind.RESOURCE,
                    leftResource.id(), rightResource.id(), changes));
        }

        Map<String, List<ResourceModel>> leftByHash = indexByHash(left.values().stream()
                .filter(resource -> !common.contains(resource.path())).toList());
        Map<String, List<ResourceModel>> rightByHash = indexByHash(right.values().stream()
                .filter(resource -> !common.contains(resource.path())).toList());
        Set<String> movedLeft = new HashSet<>();
        Set<String> movedRight = new HashSet<>();
        for (Map.Entry<String, List<ResourceModel>> entry : leftByHash.entrySet()) {
            List<ResourceModel> rightMatches = rightByHash.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() == 1 && rightMatches.size() == 1) {
                ResourceModel leftResource = entry.getValue().getFirst();
                ResourceModel rightResource = rightMatches.getFirst();
                if (detachedLeft.contains(leftResource.id()) || detachedRight.contains(rightResource.id())) {
                    continue;
                }
                movedLeft.add(leftResource.path());
                movedRight.add(rightResource.path());
                nodes.add(new DiffNode("resource:" + leftResource.path(),
                        leftResource.path() + " → " + rightResource.path(), EntityKind.RESOURCE,
                        leftResource.id(), rightResource.id(), Set.of(ChangeKind.MOVED)));
            }
        }
        left.values().stream()
                .filter(resource -> !common.contains(resource.path()) && !movedLeft.contains(resource.path()))
                .forEach(resource -> nodes.add(new DiffNode("resource:L:" + resource.path(), resource.path(),
                        EntityKind.RESOURCE, resource.id(), null, Set.of(ChangeKind.REMOVED),
                        resolution(resource.id(), true, overrides))));
        right.values().stream()
                .filter(resource -> !common.contains(resource.path()) && !movedRight.contains(resource.path()))
                .forEach(resource -> nodes.add(new DiffNode("resource:R:" + resource.path(), resource.path(),
                        EntityKind.RESOURCE, null, resource.id(), Set.of(ChangeKind.ADDED),
                        resolution(resource.id(), false, overrides))));
    }

    private static ResolutionStatus resolution(EntityId id, boolean left, ComparisonOverrides overrides) {
        if (left && overrides.confirmedRemoved().contains(id)) {
            return ResolutionStatus.CONFIRMED_REMOVED;
        }
        if (!left && overrides.confirmedAdded().contains(id)) {
            return ResolutionStatus.CONFIRMED_ADDED;
        }
        return ResolutionStatus.NONE;
    }

    private static Map<String, List<ResourceModel>> indexByHash(List<ResourceModel> resources) {
        return resources.stream().collect(Collectors.groupingBy(
                ResourceModel::sha256, LinkedHashMap::new, Collectors.toList()));
    }

    private static FieldModel findField(ArtifactSnapshot artifact, EntityId id) {
        return artifact.classes().get(id.owner()).fields().stream()
                .filter(field -> field.name().equals(id.name()) && field.descriptor().equals(id.descriptor()))
                .findFirst().orElseThrow();
    }

    private static MethodModel findMethod(ArtifactSnapshot artifact, EntityId id) {
        return artifact.classes().get(id.owner()).methods().stream()
                .filter(method -> method.name().equals(id.name()) && method.descriptor().equals(id.descriptor()))
                .findFirst().orElseThrow();
    }

    private static String display(MatchDecision match) {
        return match.left().externalName().equals(match.right().externalName())
                ? match.left().externalName()
                : match.left().externalName() + " → " + match.right().externalName();
    }
}
