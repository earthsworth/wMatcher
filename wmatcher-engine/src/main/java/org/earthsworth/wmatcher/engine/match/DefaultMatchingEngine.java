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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DetachedPair;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.MatchingUpdate;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.core.model.ScoreBreakdown;
import org.earthsworth.wmatcher.core.service.MatchingEngine;
import org.earthsworth.wmatcher.core.service.MatchingSession;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.objectweb.asm.Opcodes;

public final class DefaultMatchingEngine implements MatchingEngine {
    private static final long SESSION_CACHE_BUDGET = 512L * 1024 * 1024;
    private static final Map<ClassScoreKey, ScoreBreakdown> CLASS_SCORE_CACHE = boundedCache(100_000);
    private static final Map<FieldScoreKey, ScoreBreakdown> FIELD_SCORE_CACHE = boundedCache(50_000);
    private static final Map<MethodScoreKey, ScoreBreakdown> METHOD_SCORE_CACHE = boundedCache(100_000);
    private final Map<CacheKey, MatchResult> resultCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, MatchResult> eldest) {
                    return size() > 32;
                }
            });
    private static final Comparator<MatchDecision> MATCH_ORDER = Comparator
            .comparing((MatchDecision match) -> match.left().externalName())
            .thenComparing(match -> match.right().externalName());

    @Override
    public MatchingSession openSession(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation) {
        return new Session(left, right, policy);
    }

    @Override
    public MatchResult match(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            ComparisonOverrides overrides,
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
        Set<EntityId> detachedLeft = overrides.detachedPairs().stream()
                .map(DetachedPair::left).collect(Collectors.toSet());
        Set<EntityId> detachedRight = overrides.detachedPairs().stream()
                .map(DetachedPair::right).collect(Collectors.toSet());
        usedLeft.addAll(overrides.confirmedRemoved());
        usedRight.addAll(overrides.confirmedAdded());
        for (Map.Entry<EntityId, EntityId> locked : overrides.lockedMappings().entrySet()) {
            MatchDecision decision = decision(locked.getKey(), locked.getValue(), MatchStatus.MANUAL_CONFIRMED, 1.0,
                    Map.of("manual", 1.0));
            confirmed.add(decision);
            usedLeft.add(decision.left());
            usedRight.add(decision.right());
        }

        progress.onProgress("Matching classes", 0, left.classes().size());
        matchClasses(left, right, policy, confirmed, suggestions, rankedCandidates,
                usedLeft, usedRight, detachedLeft, detachedRight, progress, cancellation);

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
                if (semanticallyEqual(leftClass, rightClass)) {
                    matchEquivalentFields(leftClass, rightClass, confirmed, usedLeft, usedRight,
                            detachedLeft, detachedRight);
                    matchEquivalentMethods(leftClass, rightClass, confirmed, usedLeft, usedRight,
                            detachedLeft, detachedRight);
                }
                matchFields(leftClass, rightClass, policy, confirmed, suggestions, rankedCandidates,
                        usedLeft, usedRight, detachedLeft, detachedRight);
                matchMethods(leftClass, rightClass, policy, confirmed, suggestions, rankedCandidates,
                        usedLeft, usedRight, detachedLeft, detachedRight);
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
        MatchResult result = new MatchResult(confirmed, suggestions, rankedCandidates, unmatchedLeft, unmatchedRight);
        resultCache.put(new CacheKey(left.sha256(), right.sha256(), policy.version(), overrides), result);
        return result;
    }

    @Override
    public MatchResult rematch(
            ArtifactSnapshot left,
            ArtifactSnapshot right,
            MatchResult previous,
            ComparisonOverrides previousOverrides,
            ComparisonOverrides overrides,
            Set<EntityId> affectedEntities,
            MatchingPolicy policy,
            ProgressListener progress,
            CancellationToken cancellation) {
        CacheKey key = new CacheKey(left.sha256(), right.sha256(), policy.version(), overrides);
        MatchResult cached = resultCache.get(key);
        if (cached != null) return cached;
        if (!affectedEntities.isEmpty() && affectedEntities.stream().allMatch(id -> id.kind() ==
                org.earthsworth.wmatcher.core.model.EntityKind.RESOURCE)) {
            resultCache.put(key, previous);
            return previous;
        }
        return match(left, right, overrides, policy, progress, cancellation);
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
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight,
            ProgressListener progress,
            CancellationToken cancellation) {
        Map<String, List<ClassModel>> leftExact = index(left.classes().values(), DefaultMatchingEngine::exactClassKey);
        Map<String, List<ClassModel>> rightExact = index(right.classes().values(), DefaultMatchingEngine::exactClassKey);
        sortClassIndex(leftExact);
        sortClassIndex(rightExact);
        for (Map.Entry<String, List<ClassModel>> entry : leftExact.entrySet()) {
            List<ClassModel> rightMatches = rightExact.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() == 1 && rightMatches.size() == 1) {
                EntityId leftId = entry.getValue().getFirst().id();
                EntityId rightId = rightMatches.getFirst().id();
                if (!usedLeft.contains(leftId) && !usedRight.contains(rightId)
                        && !detachedLeft.contains(leftId) && !detachedRight.contains(rightId)) {
                    confirmed.add(decision(leftId, rightId, MatchStatus.EXACT, 1.0,
                            Map.of("fingerprint", 1.0)));
                    usedLeft.add(leftId);
                    usedRight.add(rightId);
                }
            }
        }

        Map<String, List<ClassModel>> rightByMethod = new HashMap<>();
        Map<String, List<ClassModel>> rightByStructure = index(
                right.classes().values(), ClassModel::structuralFingerprint);
        Map<String, List<ClassModel>> rightByBytecode = index(
                right.classes().values(), ClassModel::bytecodeFingerprint);
        Map<String, List<ClassModel>> rightByBucket = new HashMap<>();
        for (ClassModel model : right.classes().values()) {
            rightByBucket.computeIfAbsent(classBucket(model), ignored -> new ArrayList<>()).add(model);
            for (MethodModel method : model.methods()) {
                if (method.instructionCount() > 0) {
                    rightByMethod.computeIfAbsent(method.instructionFingerprint(), ignored -> new ArrayList<>()).add(model);
                }
            }
        }
        sortClassIndex(rightByStructure);
        sortClassIndex(rightByBytecode);
        sortClassIndex(rightByBucket);
        sortClassIndex(rightByMethod);

        Map<EntityId, List<CandidateScore>> scored = new LinkedHashMap<>();
        List<ClassModel> orderedLeft = left.classes().values().stream()
                .sorted(Comparator.comparing(ClassModel::internalName))
                .toList();
        long completed = 0;
        for (ClassModel leftClass : orderedLeft) {
            cancellation.throwIfCancelled();
            EntityId leftId = leftClass.id();
            // Keep ranked alternatives for already confirmed classes so a session can edit them later.
            Set<ClassModel> pool = new LinkedHashSet<>();
            ClassModel sameName = right.classes().get(leftClass.internalName());
            if (sameName != null) {
                pool.add(sameName);
            }
            List<ClassModel> exactTargets = rightExact.getOrDefault(exactClassKey(leftClass), List.of());
            addOrdered(pool, exactTargets, 500);
            addOrdered(pool, rightByStructure.getOrDefault(leftClass.structuralFingerprint(), List.of()), 500);
            addOrdered(pool, rightByBytecode.getOrDefault(leftClass.bytecodeFingerprint(), List.of()), 500);
            if (exactTargets.size() != 1) {
                for (MethodModel method : leftClass.methods()) {
                    if (method.instructionCount() > 0) {
                        List<ClassModel> sharedCode = rightByMethod.getOrDefault(
                                method.instructionFingerprint(), List.of());
                        if (sharedCode.size() <= 500) {
                            addOrdered(pool, sharedCode, 500);
                        }
                    }
                }
                int fieldTolerance = neighborTolerance(leftClass.fields().size());
                int methodTolerance = neighborTolerance(leftClass.methods().size());
                for (int fields = Math.max(0, leftClass.fields().size() - fieldTolerance);
                        fields <= leftClass.fields().size() + fieldTolerance && pool.size() < 500; fields++) {
                    for (int methods = Math.max(0, leftClass.methods().size() - methodTolerance);
                            methods <= leftClass.methods().size() + methodTolerance && pool.size() < 500; methods++) {
                        addOrdered(pool, rightByBucket.getOrDefault(
                                classBucket(classKind(leftClass), fields, methods), List.of()), 500);
                    }
                }
            }
            List<CandidateScore> classScores = pool.stream()
                    .filter(candidate -> !usedRight.contains(candidate.id()))
                    .filter(candidate -> classKind(candidate) == classKind(leftClass))
                    .limit(500)
                    .map(candidate -> classScore(leftClass, candidate,
                            exactTargets.size()))
                    .sorted(CandidateScore.ORDER)
                    .toList();
            scored.put(leftId, classScores);
            progress.onProgress("Matching classes", ++completed, orderedLeft.size());
        }
        recordRankedCandidates(scored, rankedCandidates, policy);
        acceptUniquePerfect(scored, confirmed, usedLeft, usedRight, detachedLeft, detachedRight);
        acceptMutualCandidates(scored, policy, confirmed, suggestions,
                usedLeft, usedRight, detachedLeft, detachedRight);
    }

    private static void matchFields(
            ClassModel left,
            ClassModel right,
            MatchingPolicy policy,
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> suggestions,
            Map<EntityId, List<MatchDecision>> rankedCandidates,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
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
                    .sorted(fieldPriority(leftField))
                    .limit(500)
                    .map(pair -> fieldScore(leftField, pair.field(), pair.id()))
                    .sorted(CandidateScore.ORDER)
                    .toList();
            scored.put(leftId, candidates);
        }
        recordRankedCandidates(scored, rankedCandidates, policy);
        acceptUniquePerfect(scored, confirmed, usedLeft, usedRight, detachedLeft, detachedRight);
        acceptMutualCandidates(scored, policy, confirmed, suggestions,
                usedLeft, usedRight, detachedLeft, detachedRight);
    }

    private static boolean semanticallyEqual(ClassModel left, ClassModel right) {
        return left.structuralFingerprint().equals(right.structuralFingerprint())
                && left.bytecodeFingerprint().equals(right.bytecodeFingerprint());
    }

    private static void matchEquivalentFields(
            ClassModel left,
            ClassModel right,
            List<MatchDecision> confirmed,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
        Map<String, List<FieldModel>> leftGroups = left.fields().stream().collect(Collectors.groupingBy(
                FieldModel::fingerprint, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<FieldModel>> rightGroups = right.fields().stream().collect(Collectors.groupingBy(
                FieldModel::fingerprint, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<FieldModel>> entry : leftGroups.entrySet()) {
            List<FieldModel> rightFields = rightGroups.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() != rightFields.size()) continue;
            List<FieldModel> leftFields = entry.getValue().stream()
                    .sorted(Comparator.comparing(FieldModel::name).thenComparing(FieldModel::descriptor)).toList();
            List<FieldModel> orderedRight = rightFields.stream()
                    .sorted(Comparator.comparing(FieldModel::name).thenComparing(FieldModel::descriptor)).toList();
            for (int index = 0; index < leftFields.size(); index++) {
                FieldModel oldField = leftFields.get(index);
                FieldModel newField = orderedRight.get(index);
                EntityId oldId = EntityId.fieldId(left.internalName(), oldField.name(), oldField.descriptor());
                EntityId newId = EntityId.fieldId(right.internalName(), newField.name(), newField.descriptor());
                addEquivalentDecision(oldId, newId, confirmed, usedLeft, usedRight, detachedLeft, detachedRight);
            }
        }
    }

    private static void matchEquivalentMethods(
            ClassModel left,
            ClassModel right,
            List<MatchDecision> confirmed,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
        Function<MethodModel, String> key = method -> method.structuralFingerprint()
                + ':' + method.instructionFingerprint();
        Map<String, List<MethodModel>> leftGroups = left.methods().stream().collect(Collectors.groupingBy(
                key, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<MethodModel>> rightGroups = right.methods().stream().collect(Collectors.groupingBy(
                key, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<MethodModel>> entry : leftGroups.entrySet()) {
            List<MethodModel> rightMethods = rightGroups.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() != rightMethods.size()) continue;
            List<MethodModel> leftMethods = entry.getValue().stream()
                    .sorted(Comparator.comparing(MethodModel::name).thenComparing(MethodModel::descriptor)).toList();
            List<MethodModel> orderedRight = rightMethods.stream()
                    .sorted(Comparator.comparing(MethodModel::name).thenComparing(MethodModel::descriptor)).toList();
            for (int index = 0; index < leftMethods.size(); index++) {
                MethodModel oldMethod = leftMethods.get(index);
                MethodModel newMethod = orderedRight.get(index);
                EntityId oldId = EntityId.methodId(left.internalName(), oldMethod.name(), oldMethod.descriptor());
                EntityId newId = EntityId.methodId(right.internalName(), newMethod.name(), newMethod.descriptor());
                addEquivalentDecision(oldId, newId, confirmed, usedLeft, usedRight, detachedLeft, detachedRight);
            }
        }
    }

    private static void addEquivalentDecision(
            EntityId left,
            EntityId right,
            List<MatchDecision> confirmed,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
        if (usedLeft.contains(left) || usedRight.contains(right)
                || detachedLeft.contains(left) || detachedRight.contains(right)) return;
        confirmed.add(decision(left, right, MatchStatus.EXACT, 1.0, Map.of("equivalentFingerprint", 1.0)));
        usedLeft.add(left);
        usedRight.add(right);
    }

    private static void matchMethods(
            ClassModel left,
            ClassModel right,
            MatchingPolicy policy,
            List<MatchDecision> confirmed,
            Map<EntityId, List<MatchDecision>> suggestions,
            Map<EntityId, List<MatchDecision>> rankedCandidates,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
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
                    .sorted(methodPriority(leftMethod))
                    .limit(500)
                    .map(pair -> methodScore(leftMethod, pair.method(), pair.id()))
                    .sorted(CandidateScore.ORDER)
                    .toList();
            scored.put(leftId, candidates);
        }
        recordRankedCandidates(scored, rankedCandidates, policy);
        acceptUniquePerfect(scored, confirmed, usedLeft, usedRight, detachedLeft, detachedRight);
        acceptMutualCandidates(scored, policy, confirmed, suggestions,
                usedLeft, usedRight, detachedLeft, detachedRight);
    }

    private static void acceptUniquePerfect(
            Map<EntityId, List<CandidateScore>> scored,
            List<MatchDecision> confirmed,
            Set<EntityId> usedLeft,
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
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
                if (detachedLeft.contains(entry.getKey()) || detachedRight.contains(perfect.getFirst().right())) {
                    continue;
                }
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
            Set<EntityId> usedRight,
            Set<EntityId> detachedLeft,
            Set<EntityId> detachedRight) {
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
            List<CandidateScore> candidates = entry.getValue().stream()
                    .filter(candidate -> candidate.breakdown().total() >= policy.candidateThreshold())
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            CandidateScore best = candidates.getFirst();
            double runnerUp = candidates.size() > 1 ? candidates.get(1).breakdown().total() : 0.0;
            long leftPerfectCount = candidates.stream().filter(DefaultMatchingEngine::isPerfect).count();
            boolean perfectIsUnambiguous = !isPerfect(best)
                    || leftPerfectCount == 1 && rightPerfectCounts.getOrDefault(best.right(), 0L) == 1L;
            boolean automatic = best.breakdown().total() >= policy.automaticThreshold()
                    && best.breakdown().total() - runnerUp >= policy.minimumMargin()
                    && left.equals(rightBestLeft.get(best.right()))
                    && !usedRight.contains(best.right())
                    && !detachedLeft.contains(left)
                    && !detachedRight.contains(best.right())
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

    private static CandidateScore classScore(ClassModel left, ClassModel right, int equivalentTargets) {
        ClassScoreKey key = new ClassScoreKey(
                left.internalName(), left.structuralFingerprint(), left.bytecodeFingerprint(),
                left.fields().size(), left.methods().size(), left.interfaces().size(),
                right.internalName(), right.structuralFingerprint(), right.bytecodeFingerprint(),
                right.fields().size(), right.methods().size(), right.interfaces().size(), equivalentTargets);
        return new CandidateScore(right.id(), cached(CLASS_SCORE_CACHE, key,
                () -> classBreakdown(left, right, equivalentTargets)));
    }

    private static ScoreBreakdown classBreakdown(ClassModel left, ClassModel right, int equivalentTargets) {
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
        Map<String, Double> components = new LinkedHashMap<>();
        components.put("structure", structure);
        components.put("code", code);
        components.put("shape", shape);
        components.put("hierarchy", hierarchy);
        components.put("stableName", name);
        if (exactClassKey(left).equals(exactClassKey(right))) {
            components.put("exactFingerprint", 1.0);
            components.put("equivalentTargets", (double) equivalentTargets);
        }
        double fingerprintScore = structure * 0.35 + code * 0.40 + shape * 0.15 + hierarchy * 0.05 + name * 0.05;
        double stableIdentityScore = name == 0.0 ? 0.0
                : 0.78 + structure * 0.10 + code * 0.05 + shape * 0.04 + hierarchy * 0.03;
        double total = Math.max(fingerprintScore, stableIdentityScore);
        return new ScoreBreakdown(clamp(total), components);
    }

    private static CandidateScore fieldScore(FieldModel left, FieldModel right, EntityId rightId) {
        FieldScoreKey key = new FieldScoreKey(left.name(), left.descriptor(), left.access(), left.fingerprint(),
                right.name(), right.descriptor(), right.access(), right.fingerprint());
        return new CandidateScore(rightId, cached(FIELD_SCORE_CACHE, key, () -> fieldBreakdown(left, right)));
    }

    private static ScoreBreakdown fieldBreakdown(FieldModel left, FieldModel right) {
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
        return new ScoreBreakdown(clamp(total), components);
    }

    private static CandidateScore methodScore(MethodModel left, MethodModel right, EntityId rightId) {
        MethodScoreKey key = new MethodScoreKey(left.name(), left.descriptor(), left.instructionFingerprint(),
                left.structuralFingerprint(), right.name(), right.descriptor(), right.instructionFingerprint(),
                right.structuralFingerprint());
        return new CandidateScore(rightId, cached(METHOD_SCORE_CACHE, key, () -> methodBreakdown(left, right)));
    }

    private static ScoreBreakdown methodBreakdown(MethodModel left, MethodModel right) {
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
        return new ScoreBreakdown(clamp(total), components);
    }

    private static <K> ScoreBreakdown cached(
            Map<K, ScoreBreakdown> cache, K key, Supplier<ScoreBreakdown> computation) {
        synchronized (cache) {
            ScoreBreakdown existing = cache.get(key);
            if (existing != null) return existing;
            ScoreBreakdown created = computation.get();
            cache.put(key, created);
            return created;
        }
    }

    private static <K, V> Map<K, V> boundedCache(int maximum) {
        return new LinkedHashMap<>(maximum, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maximum;
            }
        };
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
        artifact.resources().values().forEach(resource -> result.add(resource.id()));
        return result;
    }

    private static void validateOverrides(ComparisonOverrides overrides, Set<EntityId> left, Set<EntityId> right) {
        overrides.lockedMappings().forEach((leftId, rightId) -> {
            if (!left.contains(leftId) || !right.contains(rightId)) {
                throw new IllegalArgumentException("Locked mapping references an entity that is not present: " + leftId);
            }
        });
        if (!left.containsAll(overrides.confirmedRemoved())) {
            throw new IllegalArgumentException("Confirmed removals contain entities not present on the old side");
        }
        if (!right.containsAll(overrides.confirmedAdded())) {
            throw new IllegalArgumentException("Confirmed additions contain entities not present on the new side");
        }
        for (DetachedPair pair : overrides.detachedPairs()) {
            if (!left.contains(pair.left()) || !right.contains(pair.right())) {
                throw new IllegalArgumentException("Detached pair references an entity that is not present");
            }
        }
    }

    private static <T> Map<String, List<T>> index(Collection<T> values, Function<T, String> keyFunction) {
        return values.stream().collect(Collectors.groupingBy(keyFunction, LinkedHashMap::new, Collectors.toList()));
    }

    private static String exactClassKey(ClassModel model) {
        return model.structuralFingerprint() + ':' + model.bytecodeFingerprint();
    }

    private static String classBucket(ClassModel model) {
        return classBucket(classKind(model), model.fields().size(), model.methods().size());
    }

    private static String classBucket(int kind, int fields, int methods) {
        return kind + ":" + fields + ':' + methods;
    }

    private static int neighborTolerance(int count) {
        return Math.max(1, Math.min(4, (int) Math.ceil(count * 0.10)));
    }

    private static void addOrdered(Set<ClassModel> target, Collection<ClassModel> candidates, int maximum) {
        for (ClassModel candidate : candidates) {
            if (target.size() >= maximum) {
                return;
            }
            target.add(candidate);
        }
    }

    private static void sortClassIndex(Map<String, List<ClassModel>> index) {
        index.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparing(ClassModel::internalName)).toList());
    }

    private static Comparator<FieldPair> fieldPriority(FieldModel left) {
        return Comparator
                .comparingInt((FieldPair pair) -> left.name().equals(pair.field().name())
                        && left.descriptor().equals(pair.field().descriptor()) ? 0 : 1)
                .thenComparingInt(pair -> left.fingerprint().equals(pair.field().fingerprint()) ? 0 : 1)
                .thenComparingInt(pair -> descriptorShape(left.descriptor())
                        .equals(descriptorShape(pair.field().descriptor())) ? 0 : 1)
                .thenComparing(pair -> pair.id().externalName());
    }

    private static Comparator<MethodPair> methodPriority(MethodModel left) {
        return Comparator
                .comparingInt((MethodPair pair) -> left.name().equals(pair.method().name())
                        && left.descriptor().equals(pair.method().descriptor()) ? 0 : 1)
                .thenComparingInt(pair -> left.instructionFingerprint()
                        .equals(pair.method().instructionFingerprint()) ? 0 : 1)
                .thenComparingInt(pair -> left.structuralFingerprint()
                        .equals(pair.method().structuralFingerprint()) ? 0 : 1)
                .thenComparingInt(pair -> descriptorShape(left.descriptor())
                        .equals(descriptorShape(pair.method().descriptor())) ? 0 : 1)
                .thenComparing(pair -> pair.id().externalName());
    }

    private static int classKind(ClassModel model) {
        return model.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_RECORD);
    }

    private static boolean specialMethodCompatible(String left, String right) {
        boolean leftSpecial = left.startsWith("<");
        boolean rightSpecial = right.startsWith("<");
        return leftSpecial == rightSpecial && (!leftSpecial || left.equals(right));
    }

    private static boolean isMember(EntityId id) {
        return id.kind() == EntityKind.FIELD || id.kind() == EntityKind.METHOD;
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

    private final class Session implements MatchingSession {
        private final ArtifactSnapshot left;
        private final ArtifactSnapshot right;
        private final MatchingPolicy policy;
        private final Set<EntityId> allLeftEntities;
        private final Set<EntityId> allRightEntities;
        private final List<ClassModel> orderedLeftClasses;
        private final Map<String, List<ClassModel>> rightExact;
        private final Map<String, List<ClassModel>> rightByStructure;
        private final Map<String, List<ClassModel>> rightByBytecode;
        private final Map<String, List<ClassModel>> rightByBucket = new HashMap<>();
        private final Map<String, List<ClassModel>> rightByMethod = new HashMap<>();
        private final LinkedHashMap<EntityId, List<MatchDecision>> classCandidates =
                new LinkedHashMap<>(16, 0.75f, true);
        private final Map<EntityId, Set<EntityId>> reverseCandidates = new HashMap<>();
        private final LinkedHashMap<ComparisonOverrides, WeightedResult> cache = new LinkedHashMap<>(16, 0.75f, true);
        private long cacheWeight;
        private long graphWeight;
        private boolean closed;

        private Session(ArtifactSnapshot left, ArtifactSnapshot right, MatchingPolicy policy) {
            this.left = left;
            this.right = right;
            this.policy = policy;
            this.allLeftEntities = allEntities(left);
            this.allRightEntities = allEntities(right);
            this.orderedLeftClasses = left.classes().values().stream()
                    .sorted(Comparator.comparing(ClassModel::internalName)).toList();
            this.rightExact = index(right.classes().values(), DefaultMatchingEngine::exactClassKey);
            this.rightByStructure = index(right.classes().values(), ClassModel::structuralFingerprint);
            this.rightByBytecode = index(right.classes().values(), ClassModel::bytecodeFingerprint);
            for (ClassModel model : right.classes().values()) {
                rightByBucket.computeIfAbsent(classBucket(model), ignored -> new ArrayList<>()).add(model);
                for (MethodModel method : model.methods()) {
                    if (method.instructionCount() > 0) {
                        rightByMethod.computeIfAbsent(method.instructionFingerprint(), ignored -> new ArrayList<>())
                                .add(model);
                    }
                }
            }
            sortClassIndex(rightExact);
            sortClassIndex(rightByStructure);
            sortClassIndex(rightByBytecode);
            sortClassIndex(rightByBucket);
            sortClassIndex(rightByMethod);
        }

        @Override
        public synchronized MatchResult match(
                ComparisonOverrides overrides,
                ProgressListener progress,
                CancellationToken cancellation) {
            ensureOpen();
            MatchResult cached = cached(overrides);
            if (cached != null) return cached;
            MatchResult result = DefaultMatchingEngine.this.match(
                    left, right, overrides, policy, progress, cancellation);
            rememberCandidates(result);
            cache(overrides, result);
            return result;
        }

        @Override
        public synchronized MatchResult rematch(
                MatchResult previous,
                ComparisonOverrides previousOverrides,
                ComparisonOverrides overrides,
                Set<EntityId> affectedEntities,
                ProgressListener progress,
                CancellationToken cancellation) {
            ensureOpen();
            MatchResult cached = cached(overrides);
            if (cached != null) return cached;
            rememberCandidates(previous);
            if (affectedEntities.stream().allMatch(id -> id.kind() == EntityKind.RESOURCE)) {
                cache(overrides, previous);
                return previous;
            }
            MatchResult result = resolveFromCandidateGraph(
                    previous, overrides, affectedEntities, progress, cancellation);
            rememberCandidates(result);
            cache(overrides, result);
            return result;
        }

        @Override
        public synchronized MatchingUpdate rematchUpdate(
                MatchResult previous,
                ComparisonOverrides previousOverrides,
                ComparisonOverrides overrides,
                Set<EntityId> affectedEntities,
                ProgressListener progress,
                CancellationToken cancellation) {
            MatchResult result = rematch(previous, previousOverrides, overrides, affectedEntities,
                    progress, cancellation);
            Set<EntityId> expanded = new HashSet<>(affectedEntities);
            expanded.addAll(affectedClassClosure(affectedEntities));
            return new MatchingUpdate(result, expanded, false);
        }

        private MatchResult resolveFromCandidateGraph(
                MatchResult previous,
                ComparisonOverrides overrides,
                Set<EntityId> affectedEntities,
                ProgressListener progress,
                CancellationToken cancellation) {
            validateOverrides(overrides, allLeftEntities, allRightEntities);
            Set<EntityId> affectedClasses = affectedClassClosure(affectedEntities);
            Set<String> affectedLeftNames = affectedClasses.stream().map(EntityId::name)
                    .filter(left.classes()::containsKey).collect(Collectors.toSet());
            Set<String> affectedRightNames = affectedClasses.stream().map(EntityId::name)
                    .filter(right.classes()::containsKey).collect(Collectors.toSet());
            Set<EntityId> detachedLeft = overrides.detachedPairs().stream()
                    .map(DetachedPair::left).collect(Collectors.toSet());
            Set<EntityId> detachedRight = overrides.detachedPairs().stream()
                    .map(DetachedPair::right).collect(Collectors.toSet());
            Set<EntityId> usedLeft = new HashSet<>(overrides.confirmedRemoved());
            Set<EntityId> usedRight = new HashSet<>(overrides.confirmedAdded());
            List<MatchDecision> preserved = new ArrayList<>(previous.confirmed().size());
            List<MatchDecision> removed = new ArrayList<>();
            for (MatchDecision decision : previous.confirmed()) {
                if (touchesAffectedClass(decision, affectedLeftNames, affectedRightNames)) {
                    removed.add(decision);
                } else {
                    preserved.add(decision);
                    usedLeft.add(decision.left());
                    usedRight.add(decision.right());
                }
            }
            List<MatchDecision> additions = new ArrayList<>();
            Map<EntityId, List<MatchDecision>> suggestions = new LinkedHashMap<>(previous.candidates());
            Map<EntityId, List<MatchDecision>> ranked = new LinkedHashMap<>(previous.rankedCandidates());
            suggestions.keySet().removeIf(id -> touchesAffectedClass(id, affectedLeftNames));
            ranked.keySet().removeIf(id -> touchesAffectedClass(id, affectedLeftNames));

            overrides.lockedMappings().entrySet().stream()
                    .filter(entry -> entry.getKey().kind() == EntityKind.CLASS)
                    .filter(entry -> affectedClasses.contains(entry.getKey())
                            || affectedClasses.contains(entry.getValue()))
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                    .forEach(entry -> addManual(entry.getKey(), entry.getValue(), additions, ranked,
                            usedLeft, usedRight));

            Map<EntityId, List<MatchDecision>> available = new LinkedHashMap<>();
            for (ClassModel model : orderedLeftClasses) {
                EntityId leftId = model.id();
                if (!affectedLeftNames.contains(model.internalName())) continue;
                ensureClassCandidates(model);
                List<MatchDecision> candidates = classCandidates.getOrDefault(leftId, List.of()).stream()
                        .filter(candidate -> !overrides.confirmedAdded().contains(candidate.right()))
                        .filter(candidate -> right.classes().containsKey(candidate.right().name()))
                        .sorted(Comparator.comparingDouble((MatchDecision candidate) -> candidate.score().total())
                                .reversed().thenComparing(candidate -> candidate.right().externalName()))
                        .limit(20)
                        .toList();
                ranked.put(leftId, candidates.stream().limit(policy.maxCandidates()).toList());
                if (!usedLeft.contains(leftId) && !detachedLeft.contains(leftId)) {
                    available.put(leftId, candidates.stream()
                            .filter(candidate -> !detachedRight.contains(candidate.right()))
                            .toList());
                }
            }

            Map<EntityId, Long> perfectTargetCounts = available.values().stream().flatMap(List::stream)
                    .filter(candidate -> candidate.score().total() >= 1.0 - 1.0e-12)
                    .collect(Collectors.groupingBy(MatchDecision::right, Collectors.counting()));
            Map<EntityId, EntityId> targetBestLeft = new HashMap<>();
            Map<EntityId, Double> targetBestScore = new HashMap<>();
            available.forEach((leftId, candidates) -> candidates.forEach(candidate -> {
                double oldScore = targetBestScore.getOrDefault(candidate.right(), -1.0);
                EntityId oldLeft = targetBestLeft.get(candidate.right());
                if (candidate.score().total() > oldScore
                        || candidate.score().total() == oldScore && (oldLeft == null
                        || leftId.externalName().compareTo(oldLeft.externalName()) < 0)) {
                    targetBestScore.put(candidate.right(), candidate.score().total());
                    targetBestLeft.put(candidate.right(), leftId);
                }
            }));

            for (Map.Entry<EntityId, List<MatchDecision>> entry : available.entrySet()) {
                cancellation.throwIfCancelled();
                EntityId leftId = entry.getKey();
                if (usedLeft.contains(leftId)) continue;
                List<MatchDecision> candidates = entry.getValue().stream()
                        .filter(candidate -> !usedRight.contains(candidate.right()))
                        .toList();
                if (candidates.isEmpty()) continue;
                MatchDecision best = candidates.getFirst();
                long leftPerfect = candidates.stream()
                        .filter(candidate -> candidate.score().total() >= 1.0 - 1.0e-12).count();
                boolean uniquePerfect = best.score().total() >= 1.0 - 1.0e-12
                        && leftPerfect == 1
                        && perfectTargetCounts.getOrDefault(best.right(), 0L) == 1L;
                double runnerUp = candidates.size() > 1 ? candidates.get(1).score().total() : 0.0;
                boolean automatic = uniquePerfect || best.score().total() >= policy.automaticThreshold()
                        && best.score().total() - runnerUp >= policy.minimumMargin()
                        && leftId.equals(targetBestLeft.get(best.right()));
                if (automatic) {
                    MatchStatus status = uniquePerfect ? MatchStatus.EXACT : MatchStatus.AUTO_CONFIRMED;
                    additions.add(new MatchDecision(leftId, best.right(), status, best.score()));
                    usedLeft.add(leftId);
                    usedRight.add(best.right());
                } else {
                    List<MatchDecision> visible = candidates.stream()
                            .filter(candidate -> candidate.score().total() >= policy.candidateThreshold())
                            .limit(policy.maxCandidates())
                            .map(candidate -> new MatchDecision(leftId, candidate.right(),
                                    MatchStatus.SUGGESTED, candidate.score()))
                            .toList();
                    if (!visible.isEmpty()) suggestions.put(leftId, visible);
                }
            }

            overrides.lockedMappings().entrySet().stream()
                    .filter(entry -> entry.getKey().kind() != EntityKind.CLASS)
                    .filter(entry -> touchesAffectedClass(entry.getKey(), affectedLeftNames)
                            || touchesAffectedClass(entry.getValue(), affectedRightNames))
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::externalName)))
                    .forEach(entry -> addManual(entry.getKey(), entry.getValue(), additions, ranked,
                            usedLeft, usedRight));

            List<MatchDecision> classMatches = additions.stream()
                    .filter(match -> match.left().kind() == EntityKind.CLASS)
                    .toList();
            long completed = 0;
            for (MatchDecision classMatch : classMatches) {
                cancellation.throwIfCancelled();
                ClassModel leftClass = left.classes().get(classMatch.left().name());
                ClassModel rightClass = right.classes().get(classMatch.right().name());
                if (semanticallyEqual(leftClass, rightClass)) {
                    matchEquivalentFields(leftClass, rightClass, additions, usedLeft, usedRight,
                            detachedLeft, detachedRight);
                    matchEquivalentMethods(leftClass, rightClass, additions, usedLeft, usedRight,
                            detachedLeft, detachedRight);
                }
                matchFields(leftClass, rightClass, policy, additions, suggestions, ranked,
                        usedLeft, usedRight, detachedLeft, detachedRight);
                matchMethods(leftClass, rightClass, policy, additions, suggestions, ranked,
                        usedLeft, usedRight, detachedLeft, detachedRight);
                progress.onProgress("Matching affected members", ++completed, classMatches.size());
            }

            additions.sort(MATCH_ORDER);
            List<MatchDecision> confirmed = mergeMatches(preserved, additions);
            Set<EntityId> unmatchedLeft = new LinkedHashSet<>(previous.unmatchedLeft());
            Set<EntityId> unmatchedRight = new LinkedHashSet<>(previous.unmatchedRight());
            removed.forEach(match -> {
                unmatchedLeft.add(match.left());
                unmatchedRight.add(match.right());
            });
            additions.forEach(match -> {
                unmatchedLeft.remove(match.left());
                unmatchedRight.remove(match.right());
            });
            return new MatchResult(confirmed, suggestions, ranked, unmatchedLeft, unmatchedRight);
        }

        private void ensureClassCandidates(ClassModel leftClass) {
            if (classCandidates.containsKey(leftClass.id())) return;
            Set<ClassModel> pool = new LinkedHashSet<>();
            ClassModel sameName = right.classes().get(leftClass.internalName());
            if (sameName != null) pool.add(sameName);
            List<ClassModel> exactTargets = rightExact.getOrDefault(exactClassKey(leftClass), List.of());
            addOrdered(pool, exactTargets, 500);
            addOrdered(pool, rightByStructure.getOrDefault(leftClass.structuralFingerprint(), List.of()), 500);
            addOrdered(pool, rightByBytecode.getOrDefault(leftClass.bytecodeFingerprint(), List.of()), 500);
            if (exactTargets.size() != 1) {
                for (MethodModel method : leftClass.methods()) {
                    if (method.instructionCount() > 0) {
                        List<ClassModel> shared = rightByMethod.getOrDefault(
                                method.instructionFingerprint(), List.of());
                        if (shared.size() <= 500) addOrdered(pool, shared, 500);
                    }
                }
                int fieldTolerance = neighborTolerance(leftClass.fields().size());
                int methodTolerance = neighborTolerance(leftClass.methods().size());
                for (int fields = Math.max(0, leftClass.fields().size() - fieldTolerance);
                        fields <= leftClass.fields().size() + fieldTolerance && pool.size() < 500; fields++) {
                    for (int methods = Math.max(0, leftClass.methods().size() - methodTolerance);
                            methods <= leftClass.methods().size() + methodTolerance && pool.size() < 500; methods++) {
                        addOrdered(pool, rightByBucket.getOrDefault(
                                classBucket(classKind(leftClass), fields, methods), List.of()), 500);
                    }
                }
            }
            List<MatchDecision> candidates = pool.stream()
                    .filter(candidate -> classKind(candidate) == classKind(leftClass))
                    .limit(500)
                    .map(candidate -> new MatchDecision(leftClass.id(), candidate.id(), MatchStatus.SUGGESTED,
                            classScore(leftClass, candidate,
                                    exactTargets.size()).breakdown()))
                    .sorted(Comparator.comparingDouble((MatchDecision decision) -> decision.score().total())
                            .reversed().thenComparing(decision -> decision.right().externalName()))
                    .limit(20)
                    .toList();
            mergeCandidates(leftClass.id(), candidates);
        }

        private boolean touchesAffectedClass(
                MatchDecision decision, Set<String> affectedLeftNames, Set<String> affectedRightNames) {
            return touchesAffectedClass(decision.left(), affectedLeftNames)
                    || touchesAffectedClass(decision.right(), affectedRightNames);
        }

        private boolean touchesAffectedClass(EntityId id, Set<String> affectedNames) {
            if (id.kind() == EntityKind.RESOURCE) return false;
            return affectedNames.contains(id.kind() == EntityKind.CLASS ? id.name() : id.owner());
        }

        private List<MatchDecision> mergeMatches(
                List<MatchDecision> preserved, List<MatchDecision> additions) {
            List<MatchDecision> result = new ArrayList<>(preserved.size() + additions.size());
            int leftIndex = 0;
            int rightIndex = 0;
            while (leftIndex < preserved.size() && rightIndex < additions.size()) {
                MatchDecision oldDecision = preserved.get(leftIndex);
                MatchDecision newDecision = additions.get(rightIndex);
                if (MATCH_ORDER.compare(oldDecision, newDecision) <= 0) {
                    result.add(oldDecision);
                    leftIndex++;
                } else {
                    result.add(newDecision);
                    rightIndex++;
                }
            }
            result.addAll(preserved.subList(leftIndex, preserved.size()));
            result.addAll(additions.subList(rightIndex, additions.size()));
            return result;
        }

        private void addManual(
                EntityId leftId,
                EntityId rightId,
                List<MatchDecision> confirmed,
                Map<EntityId, List<MatchDecision>> ranked,
                Set<EntityId> usedLeft,
                Set<EntityId> usedRight) {
            MatchDecision manual = decision(leftId, rightId, MatchStatus.MANUAL_CONFIRMED, 1.0,
                    Map.of("manual", 1.0));
            confirmed.add(manual);
            ranked.putIfAbsent(leftId, List.of(manual));
            usedLeft.add(leftId);
            usedRight.add(rightId);
        }

        private Set<EntityId> affectedClassClosure(Set<EntityId> affectedEntities) {
            Set<EntityId> closure = new HashSet<>();
            java.util.ArrayDeque<EntityId> pending = new java.util.ArrayDeque<>();
            for (EntityId id : affectedEntities) {
                EntityId classId = id.kind() == EntityKind.CLASS ? id
                        : isMember(id) ? EntityId.classId(id.owner()) : null;
                if (classId != null && closure.add(classId)) pending.add(classId);
            }
            while (!pending.isEmpty() && closure.size() <= 1_000) {
                EntityId current = pending.removeFirst();
                for (MatchDecision candidate : classCandidates.getOrDefault(current, List.of())) {
                    if (closure.add(candidate.right())) pending.addLast(candidate.right());
                }
                for (EntityId competitor : reverseCandidates.getOrDefault(current, Set.of())) {
                    if (closure.add(competitor)) pending.addLast(competitor);
                }
            }
            return closure;
        }

        private void rememberCandidates(MatchResult result) {
            result.rankedCandidates().forEach((leftId, candidates) -> {
                if (leftId.kind() == EntityKind.CLASS) mergeCandidates(leftId, candidates);
            });
            result.confirmed().stream()
                    .filter(match -> match.left().kind() == EntityKind.CLASS)
                    .filter(match -> match.status() != MatchStatus.MANUAL_CONFIRMED)
                    .forEach(match -> mergeCandidates(match.left(), List.of(match)));
        }

        private void mergeCandidates(EntityId leftId, List<MatchDecision> additions) {
            Map<EntityId, MatchDecision> merged = new LinkedHashMap<>();
            List<MatchDecision> oldCandidates = classCandidates.getOrDefault(leftId, List.of());
            oldCandidates.forEach(match -> merged.put(match.right(), match));
            additions.forEach(match -> merged.merge(match.right(), match,
                    (first, second) -> first.score().total() >= second.score().total() ? first : second));
            List<MatchDecision> ordered = merged.values().stream()
                    .sorted(Comparator.comparingDouble((MatchDecision match) -> match.score().total()).reversed()
                            .thenComparing(match -> match.right().externalName()))
                    .limit(20)
                    .toList();
            classCandidates.put(leftId, ordered);
            graphWeight += candidateWeight(ordered) - candidateWeight(oldCandidates);
            ordered.forEach(match -> reverseCandidates
                    .computeIfAbsent(match.right(), ignored -> new LinkedHashSet<>()).add(leftId));
            enforceBudget();
        }

        private MatchResult cached(ComparisonOverrides overrides) {
            WeightedResult result = cache.get(overrides);
            return result == null ? null : result.result();
        }

        private void cache(ComparisonOverrides overrides, MatchResult result) {
            long weight = estimateWeight(result);
            WeightedResult old = cache.put(overrides, new WeightedResult(result, weight));
            if (old != null) cacheWeight -= old.weight();
            cacheWeight += weight;
            enforceBudget();
        }

        private void enforceBudget() {
            while (cacheWeight + graphWeight > SESSION_CACHE_BUDGET && !cache.isEmpty()) {
                Map.Entry<ComparisonOverrides, WeightedResult> eldest = cache.entrySet().iterator().next();
                cacheWeight -= eldest.getValue().weight();
                cache.remove(eldest.getKey());
            }
            while (cacheWeight + graphWeight > SESSION_CACHE_BUDGET && !classCandidates.isEmpty()) {
                Map.Entry<EntityId, List<MatchDecision>> eldest = classCandidates.entrySet().iterator().next();
                classCandidates.remove(eldest.getKey());
                graphWeight -= candidateWeight(eldest.getValue());
                for (MatchDecision candidate : eldest.getValue()) {
                    Set<EntityId> reverse = reverseCandidates.get(candidate.right());
                    if (reverse != null) {
                        reverse.remove(eldest.getKey());
                        if (reverse.isEmpty()) reverseCandidates.remove(candidate.right());
                    }
                }
            }
        }

        private long candidateWeight(List<MatchDecision> candidates) {
            return 160L * candidates.size() + 64L;
        }

        private long estimateWeight(MatchResult result) {
            long decisions = result.confirmed().size();
            decisions += result.candidates().values().stream().mapToLong(List::size).sum();
            decisions += result.rankedCandidates().values().stream().mapToLong(List::size).sum();
            return 256L * decisions + 64L * (result.unmatchedLeft().size() + result.unmatchedRight().size());
        }

        @Override public long maximumCacheWeight() { return SESSION_CACHE_BUDGET; }
        @Override public synchronized long currentCacheWeight() { return cacheWeight + graphWeight; }

        @Override
        public synchronized void close() {
            cache.clear();
            classCandidates.clear();
            reverseCandidates.clear();
            cacheWeight = 0;
            graphWeight = 0;
            closed = true;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Matching session is closed");
        }
    }

    private record WeightedResult(MatchResult result, long weight) { }

    private record CandidateScore(EntityId right, ScoreBreakdown breakdown) {
        private static final Comparator<CandidateScore> ORDER = Comparator
                .comparingDouble((CandidateScore candidate) -> candidate.breakdown.total()).reversed()
                .thenComparing(candidate -> candidate.right.externalName());
    }

    private record FieldPair(FieldModel field, EntityId id) { }

    private record MethodPair(MethodModel method, EntityId id) { }

    private record CacheKey(String leftHash, String rightHash, String policy, ComparisonOverrides overrides) { }

    private record ClassScoreKey(
            String leftName, String leftStructure, String leftCode, int leftFields, int leftMethods,
            int leftInterfaces, String rightName, String rightStructure, String rightCode,
            int rightFields, int rightMethods, int rightInterfaces, int equivalentTargets) { }

    private record FieldScoreKey(
            String leftName, String leftDescriptor, int leftAccess, String leftFingerprint,
            String rightName, String rightDescriptor, int rightAccess, String rightFingerprint) { }

    private record MethodScoreKey(
            String leftName, String leftDescriptor, String leftCode, String leftStructure,
            String rightName, String rightDescriptor, String rightCode, String rightStructure) { }
}
