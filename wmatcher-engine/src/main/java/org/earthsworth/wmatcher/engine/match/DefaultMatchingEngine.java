package org.earthsworth.wmatcher.engine.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.core.model.ScoreBreakdown;
import org.earthsworth.wmatcher.core.service.MatchingEngine;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.objectweb.asm.Opcodes;

public final class DefaultMatchingEngine implements MatchingEngine {
    private static final Comparator<MatchDecision> MATCH_ORDER = Comparator
            .comparing((MatchDecision match) -> match.left().externalName())
            .thenComparing(match -> match.right().externalName());

    @Override
    public MatchResult match(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MappingOverrides overrides,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Set<EntityId> allLeft = allEntities(left);
        Set<EntityId> allRight = allEntities(right);
        validateOverrides(overrides, allLeft, allRight);

        List<MatchDecision> confirmed = new ArrayList<>();
        Map<EntityId, List<MatchDecision>> suggestions = new LinkedHashMap<>();
        Map<EntityId, List<MatchDecision>> rankedCandidates = new LinkedHashMap<>();
        Set<EntityId> usedLeft = new HashSet<>();
        Set<EntityId> usedRight = new HashSet<>();
        for (Map.Entry<EntityId, EntityId> locked : overrides.locked().entrySet()) {
            MatchDecision decision = decision(locked.getKey(), locked.getValue(), MatchStatus.MANUAL_LOCKED, 1.0,
                    Map.of("manual", 1.0));
            confirmed.add(decision);
            usedLeft.add(decision.left());
            usedRight.add(decision.right());
        }

        progress.onProgress("Matching classes", 0, left.classes().size());
        matchClasses(left, right, policy, confirmed, suggestions, rankedCandidates,
                usedLeft, usedRight, progress, cancellation);

        Map<String, String> classPairs = confirmed.stream()
                .filter(match -> match.left().kind() == org.earthsworth.wmatcher.core.model.EntityKind.CLASS)
                .collect(Collectors.toMap(
                        match -> match.left().name(),
                        match -> match.right().name(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        long completed = 0;
        for (Map.Entry<String, String> pair : classPairs.entrySet()) {
            cancellation.throwIfCancelled();
            ClassModel leftClass = left.classes().get(pair.getKey());
            ClassModel rightClass = right.classes().get(pair.getValue());
            if (leftClass != null && rightClass != null) {
                matchFields(leftClass, rightClass, policy, confirmed, suggestions, rankedCandidates,
                        usedLeft, usedRight);
                matchMethods(leftClass, rightClass, policy, confirmed, suggestions, rankedCandidates,
                        usedLeft, usedRight);
            }
            progress.onProgress("Matching members", ++completed, classPairs.size());
        }

        confirmed.sort(MATCH_ORDER);
        suggestions.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparingDouble((MatchDecision match) -> match.score().total()).reversed()
                        .thenComparing(match -> match.right().externalName()))
                .limit(policy.maxCandidates())
                .toList());
        Set<EntityId> unmatchedLeft = new LinkedHashSet<>(allLeft);
        Set<EntityId> unmatchedRight = new LinkedHashSet<>(allRight);
        confirmed.forEach(match -> {
            unmatchedLeft.remove(match.left());
            unmatchedRight.remove(match.right());
        });
        return new MatchResult(confirmed, suggestions, rankedCandidates, unmatchedLeft, unmatchedRight);
    }

    private static void matchClasses(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchingPolicy policy,
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> suggestions,
            Map<EntityId, List<MatchDecision>> rankedCandidates,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            ProgressListener progress,
            CancellationToken cancellation) {
        Map<String, List<ClassModel>> leftExact = index(left.classes().values(), DefaultMatchingEngine::exactClassKey);
        Map<String, List<ClassModel>> rightExact = index(right.classes().values(), DefaultMatchingEngine::exactClassKey);
        for (Map.Entry<String, List<ClassModel>> entry : leftExact.entrySet()) {
            List<ClassModel> rightMatches = rightExact.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() == 1 && rightMatches.size() == 1) {
                EntityId leftId = entry.getValue().getFirst().id();
                EntityId rightId = rightMatches.getFirst().id();
                if (!usedLeft.contains(leftId) && !usedRight.contains(rightId)) {
                    confirmed.add(decision(leftId, rightId, MatchStatus.EXACT, 1.0,
                            Map.of("fingerprint", 1.0)));
                    usedLeft.add(leftId);
                    usedRight.add(rightId);
                }
            }
        }

        Map<String, List<ClassModel>> rightByMethod = new HashMap<>();
        Map<String, List<ClassModel>> rightByBucket = new HashMap<>();
        for (ClassModel model : right.classes().values()) {
            rightByBucket.computeIfAbsent(classBucket(model), ignored -> new ArrayList<>()).add(model);
            for (MethodModel method : model.methods()) {
                if (method.instructionCount() > 0) {
                    rightByMethod.computeIfAbsent(method.instructionFingerprint(), ignored -> new ArrayList<>()).add(model);
                }
            }
        }

        Map<EntityId, List<CandidateScore>> scored = new LinkedHashMap<>();
        List<ClassModel> orderedLeft = left.classes().values().stream()
                .sorted(Comparator.comparing(ClassModel::internalName))
                .toList();
        long completed = 0;
        for (ClassModel leftClass : orderedLeft) {
            cancellation.throwIfCancelled();
            EntityId leftId = leftClass.id();
            if (!usedLeft.contains(leftId)) {
                Set<ClassModel> pool = new LinkedHashSet<>();
                for (MethodModel method : leftClass.methods()) {
                    if (method.instructionCount() > 0) {
                        List<ClassModel> sharedCode = rightByMethod.getOrDefault(
                                method.instructionFingerprint(), List.of());
                        if (sharedCode.size() <= 500) {
                            pool.addAll(sharedCode);
                        }
                    }
                }
                ClassModel sameName = right.classes().get(leftClass.internalName());
                if (sameName != null) {
                    pool.add(sameName);
                }
                List<ClassModel> shapeBucket = rightByBucket.getOrDefault(classBucket(leftClass), List.of());
                if (shapeBucket.size() <= 200) {
                    pool.addAll(shapeBucket);
                } else if (pool.isEmpty()) {
                    shapeBucket.stream().sorted(Comparator.comparing(ClassModel::internalName)).limit(200)
                            .forEach(pool::add);
                }
                Set<ClassModel> candidatePool = pool;
                if (pool.size() > 200) {
                    candidatePool = pool.stream()
                            .sorted(Comparator.comparing(ClassModel::internalName))
                            .limit(200)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                }
                List<CandidateScore> classScores = candidatePool.stream()
                        .filter(candidate -> !usedRight.contains(candidate.id()))
                        .filter(candidate -> classKind(candidate) == classKind(leftClass))
                        .map(candidate -> classScore(leftClass, candidate))
                        .filter(score -> score.breakdown().total() >= policy.candidateThreshold())
                        .sorted(CandidateScore.ORDER)
                        .limit(Math.max(20, policy.maxCandidates() * 4L))
                        .toList();
                scored.put(leftId, classScores);
            }
            progress.onProgress("Matching classes", ++completed, orderedLeft.size());
        }
        recordRankedCandidates(scored, rankedCandidates, policy);
        acceptUniquePerfect(scored, confirmed, usedLeft, usedRight);
        acceptMutualCandidates(scored, policy, confirmed, suggestions, usedLeft, usedRight);
    }

    private static void matchFields(
            ClassModel left,
            ClassModel right,
            MatchingPolicy policy,
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> suggestions,
            Map<EntityId, List<MatchDecision>> rankedCandidates,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight) {
        Map<EntityId, List<CandidateScore>> scored = new LinkedHashMap<>();
        for (FieldModel leftField : left.fields()) {
            EntityId leftId = EntityId.fieldId(left.internalName(), leftField.name(), leftField.descriptor());
            if (usedLeft.contains(leftId)) {
                continue;
            }
            List<CandidateScore> candidates = right.fields().stream()
                    .map(rightField -> new FieldPair(rightField,
                            EntityId.fieldId(right.internalName(), rightField.name(), rightField.descriptor())))
                    .filter(pair -> !usedRight.contains(pair.id()))
                    .map(pair -> fieldScore(leftField, pair.field(), pair.id()))
                    .filter(score -> score.breakdown().total() >= policy.candidateThreshold())
                    .sorted(CandidateScore.ORDER)
                    .toList();
            scored.put(leftId, candidates);
        }
        recordRankedCandidates(scored, rankedCandidates, policy);
        acceptUniquePerfect(scored, confirmed, usedLeft, usedRight);
        acceptMutualCandidates(scored, policy, confirmed, suggestions, usedLeft, usedRight);
    }

    private static void matchMethods(
            ClassModel left,
            ClassModel right,
            MatchingPolicy policy,
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> suggestions,
            Map<EntityId, List<MatchDecision>> rankedCandidates,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight) {
        Map<EntityId, List<CandidateScore>> scored = new LinkedHashMap<>();
        for (MethodModel leftMethod : left.methods()) {
            EntityId leftId = EntityId.methodId(left.internalName(), leftMethod.name(), leftMethod.descriptor());
            if (usedLeft.contains(leftId)) {
                continue;
            }
            List<CandidateScore> candidates = right.methods().stream()
                    .filter(rightMethod -> specialMethodCompatible(leftMethod.name(), rightMethod.name()))
                    .map(rightMethod -> new MethodPair(rightMethod,
                            EntityId.methodId(right.internalName(), rightMethod.name(), rightMethod.descriptor())))
                    .filter(pair -> !usedRight.contains(pair.id()))
                    .map(pair -> methodScore(leftMethod, pair.method(), pair.id()))
                    .filter(score -> score.breakdown().total() >= policy.candidateThreshold())
                    .sorted(CandidateScore.ORDER)
                    .toList();
            scored.put(leftId, candidates);
        }
        recordRankedCandidates(scored, rankedCandidates, policy);
        acceptUniquePerfect(scored, confirmed, usedLeft, usedRight);
        acceptMutualCandidates(scored, policy, confirmed, suggestions, usedLeft, usedRight);
    }

    private static void acceptUniquePerfect(
            Map<EntityId, List<CandidateScore>> scored,
            List<MatchDecision> confirmed,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight) {
        Map<EntityId, Long> rightPerfectCounts = scored.values().stream()
                .flatMap(Collection::stream)
                .filter(DefaultMatchingEngine::isPerfect)
                .collect(Collectors.groupingBy(CandidateScore::right, Collectors.counting()));
        for (Map.Entry<EntityId, List<CandidateScore>> entry : scored.entrySet()) {
            List<CandidateScore> perfect = entry.getValue().stream()
                    .filter(DefaultMatchingEngine::isPerfect)
                    .toList();
            if (perfect.size() == 1 && rightPerfectCounts.getOrDefault(perfect.getFirst().right(), 0L) == 1L
                    && !usedLeft.contains(entry.getKey()) && !usedRight.contains(perfect.getFirst().right())) {
                CandidateScore candidate = perfect.getFirst();
                confirmed.add(new MatchDecision(entry.getKey(), candidate.right(), MatchStatus.EXACT, candidate.breakdown()));
                usedLeft.add(entry.getKey());
                usedRight.add(candidate.right());
            }
        }
    }

    private static void acceptMutualCandidates(
            Map<EntityId, List<CandidateScore>> scored,
            MatchingPolicy policy,
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> suggestions,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight) {
        Map<EntityId, Long> rightPerfectCounts = scored.values().stream()
                .flatMap(Collection::stream)
                .filter(DefaultMatchingEngine::isPerfect)
                .collect(Collectors.groupingBy(CandidateScore::right, Collectors.counting()));
        Map<EntityId, CandidateScore> rightBest = new HashMap<>();
        Map<EntityId, EntityId> rightBestLeft = new HashMap<>();
        for (Map.Entry<EntityId, List<CandidateScore>> entry : scored.entrySet()) {
            for (CandidateScore candidate : entry.getValue()) {
                CandidateScore current = rightBest.get(candidate.right());
                if (current == null || CandidateScore.ORDER.compare(candidate, current) < 0) {
                    rightBest.put(candidate.right(), candidate);
                    rightBestLeft.put(candidate.right(), entry.getKey());
                }
            }
        }
        for (Map.Entry<EntityId, List<CandidateScore>> entry : scored.entrySet()) {
            EntityId left = entry.getKey();
            if (usedLeft.contains(left) || entry.getValue().isEmpty()) {
                continue;
            }
            List<CandidateScore> candidates = entry.getValue();
            CandidateScore best = candidates.getFirst();
            double runnerUp = candidates.size() > 1 ? candidates.get(1).breakdown().total() : 0.0;
            long leftPerfectCount = candidates.stream().filter(DefaultMatchingEngine::isPerfect).count();
            boolean perfectIsUnambiguous = !isPerfect(best)
                    || leftPerfectCount == 1 && rightPerfectCounts.getOrDefault(best.right(), 0L) == 1L;
            boolean automatic = best.breakdown().total() >= policy.automaticThreshold()
                    && best.breakdown().total() - runnerUp >= policy.minimumMargin()
                    && left.equals(rightBestLeft.get(best.right()))
                    && !usedRight.contains(best.right())
                    && perfectIsUnambiguous;
            if (automatic) {
                confirmed.add(new MatchDecision(left, best.right(), MatchStatus.AUTO_CONFIRMED, best.breakdown()));
                usedLeft.add(left);
                usedRight.add(best.right());
            } else {
                suggestions.put(left, candidates.stream()
                        .limit(policy.maxCandidates())
                        .map(candidate -> new MatchDecision(left, candidate.right(), MatchStatus.SUGGESTED,
                                candidate.breakdown()))
                        .toList());
            }
        }
    }

    private static void recordRankedCandidates(
            Map<EntityId, List<CandidateScore>> scored,
            Map<EntityId, List<MatchDecision>> rankedCandidates,
            MatchingPolicy policy) {
        scored.forEach((left, candidates) -> rankedCandidates.put(left, candidates.stream()
                .limit(policy.maxCandidates())
                .map(candidate -> new MatchDecision(left, candidate.right(), MatchStatus.SUGGESTED,
                        candidate.breakdown()))
                .toList()));
    }

    private static boolean isPerfect(CandidateScore candidate) {
        return candidate.breakdown().total() >= 1.0 - 1.0e-12;
    }

    private static CandidateScore classScore(ClassModel left, ClassModel right) {
        double fields = jaccard(left.fields().stream().map(FieldModel::fingerprint).toList(),
                right.fields().stream().map(FieldModel::fingerprint).toList());
        double methods = jaccard(left.methods().stream().map(MethodModel::structuralFingerprint).toList(),
                right.methods().stream().map(MethodModel::structuralFingerprint).toList());
        double code = jaccard(left.methods().stream().filter(method -> method.instructionCount() > 0)
                        .map(MethodModel::instructionFingerprint).toList(),
                right.methods().stream().filter(method -> method.instructionCount() > 0)
                        .map(MethodModel::instructionFingerprint).toList());
        double structure = (fields + methods) / 2.0;
        double shape = (closeness(left.fields().size(), right.fields().size())
                + closeness(left.methods().size(), right.methods().size())) / 2.0;
        double hierarchy = closeness(left.interfaces().size(), right.interfaces().size());
        double name = left.internalName().equals(right.internalName()) ? 1.0 : 0.0;
        Map<String, Double> components = Map.of(
                "structure", structure,
                "code", code,
                "shape", shape,
                "hierarchy", hierarchy,
                "stableName", name);
        double fingerprintScore = structure * 0.35 + code * 0.40 + shape * 0.15 + hierarchy * 0.05 + name * 0.05;
        double stableIdentityScore = name == 0.0 ? 0.0
                : 0.78 + structure * 0.10 + code * 0.05 + shape * 0.04 + hierarchy * 0.03;
        double total = Math.max(fingerprintScore, stableIdentityScore);
        return new CandidateScore(right.id(), new ScoreBreakdown(clamp(total), components));
    }

    private static CandidateScore fieldScore(FieldModel left, FieldModel right, EntityId rightId) {
        double fingerprint = left.fingerprint().equals(right.fingerprint()) ? 1.0 : 0.0;
        double descriptor = descriptorShape(left.descriptor()).equals(descriptorShape(right.descriptor())) ? 1.0 : 0.0;
        double access = left.access() == right.access() ? 1.0 : 0.0;
        double name = left.name().equals(right.name()) ? 1.0 : 0.0;
        Map<String, Double> components = Map.of(
                "fingerprint", fingerprint, "descriptor", descriptor, "access", access, "name", name);
        double total = fingerprint * 0.5 + descriptor * 0.3 + access * 0.1 + name * 0.1;
        if (name == 1.0 && left.descriptor().equals(right.descriptor())) {
            total = Math.max(total, 0.92);
        }
        return new CandidateScore(rightId, new ScoreBreakdown(clamp(total), components));
    }

    private static CandidateScore methodScore(MethodModel left, MethodModel right, EntityId rightId) {
        double code = left.instructionFingerprint().equals(right.instructionFingerprint()) ? 1.0 : 0.0;
        double structure = left.structuralFingerprint().equals(right.structuralFingerprint()) ? 1.0 : 0.0;
        double descriptor = descriptorShape(left.descriptor()).equals(descriptorShape(right.descriptor())) ? 1.0 : 0.0;
        double name = left.name().equals(right.name()) ? 1.0 : 0.0;
        Map<String, Double> components = Map.of(
                "code", code, "structure", structure, "descriptor", descriptor, "name", name);
        double total = code * 0.6 + structure * 0.25 + descriptor * 0.1 + name * 0.05;
        if (name == 1.0 && left.descriptor().equals(right.descriptor())) {
            total = Math.max(total, 0.92);
        }
        return new CandidateScore(rightId, new ScoreBreakdown(clamp(total), components));
    }

    private static Set<EntityId> allEntities(ArtifactSnapshot artifact) {
        Set<EntityId> result = new LinkedHashSet<>();
        artifact.classes().values().forEach(model -> {
            result.add(model.id());
            model.fields().forEach(field -> result.add(EntityId.fieldId(
                    model.internalName(), field.name(), field.descriptor())));
            model.methods().forEach(method -> result.add(EntityId.methodId(
                    model.internalName(), method.name(), method.descriptor())));
        });
        return result;
    }

    private static void validateOverrides(MappingOverrides overrides, Set<EntityId> left, Set<EntityId> right) {
        overrides.locked().forEach((leftId, rightId) -> {
            if (!left.contains(leftId) || !right.contains(rightId)) {
                throw new IllegalArgumentException("Locked mapping references an entity that is not present: " + leftId);
            }
        });
    }

    private static <T> Map<String, List<T>> index(Collection<T> values, Function<T, String> keyFunction) {
        return values.stream().collect(Collectors.groupingBy(keyFunction, LinkedHashMap::new, Collectors.toList()));
    }

    private static String exactClassKey(ClassModel model) {
        return model.structuralFingerprint() + ':' + model.bytecodeFingerprint();
    }

    private static String classBucket(ClassModel model) {
        return classKind(model) + ":" + model.fields().size() + ':' + model.methods().size();
    }

    private static int classKind(ClassModel model) {
        return model.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_RECORD);
    }

    private static boolean specialMethodCompatible(String left, String right) {
        boolean leftSpecial = left.startsWith("<");
        boolean rightSpecial = right.startsWith("<");
        return leftSpecial == rightSpecial && (!leftSpecial || left.equals(right));
    }

    private static String descriptorShape(String descriptor) {
        StringBuilder result = new StringBuilder();
        boolean object = false;
        for (int index = 0; index < descriptor.length(); index++) {
            char current = descriptor.charAt(index);
            if (object) {
                if (current == ';') {
                    result.append(';');
                    object = false;
                }
            } else {
                result.append(current);
                object = current == 'L';
            }
        }
        return result.toString();
    }

    private static double closeness(int left, int right) {
        return 1.0 - Math.abs(left - right) / (double) Math.max(1, Math.max(left, right));
    }

    private static double jaccard(List<String> left, List<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        Map<String, Integer> leftCounts = counts(left);
        Map<String, Integer> rightCounts = counts(right);
        Set<String> keys = new HashSet<>(leftCounts.keySet());
        keys.addAll(rightCounts.keySet());
        int intersection = 0;
        int union = 0;
        for (String key : keys) {
            intersection += Math.min(leftCounts.getOrDefault(key, 0), rightCounts.getOrDefault(key, 0));
            union += Math.max(leftCounts.getOrDefault(key, 0), rightCounts.getOrDefault(key, 0));
        }
        return union == 0 ? 1.0 : intersection / (double) union;
    }

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        values.forEach(value -> result.merge(value, 1, Integer::sum));
        return result;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static MatchDecision decision(
            EntityId left, EntityId right, MatchStatus status, double score, Map<String, Double> components) {
        return new MatchDecision(left, right, status, new ScoreBreakdown(score, components));
    }

    private record CandidateScore(EntityId right, ScoreBreakdown breakdown) {
        private static final Comparator<CandidateScore> ORDER = Comparator
                .comparingDouble((CandidateScore candidate) -> candidate.breakdown.total()).reversed()
                .thenComparing(candidate -> candidate.right.externalName());
    }

    private record FieldPair(FieldModel field, EntityId id) { }

    private record MethodPair(MethodModel method, EntityId id) { }
}
