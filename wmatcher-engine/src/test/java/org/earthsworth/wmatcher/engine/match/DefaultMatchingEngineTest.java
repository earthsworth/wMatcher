package org.earthsworth.wmatcher.engine.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MappingOverrides;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.MatchingPolicy;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.core.model.ScanOptions;
import org.earthsworth.wmatcher.core.task.CancellationToken;
import org.earthsworth.wmatcher.core.task.ProgressListener;
import org.earthsworth.wmatcher.engine.jar.JarArtifactLoader;
import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultMatchingEngineTest {
    @TempDir Path temporaryDirectory;

    @Test
    void matchesRenamedClassFieldAndMethodDeterministically() throws Exception {
        Path oldJar = TestArtifacts.writeJar(temporaryDirectory.resolve("old.jar"),
                Map.of("old/Readable.class", TestArtifacts.simpleClass("old/Readable", "calculate", 7, 10)), false);
        Path newJar = TestArtifacts.writeJar(temporaryDirectory.resolve("new.jar"),
                Map.of("a/b.class", TestArtifacts.simpleClass("a/b", "x", 7, 99)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(oldJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(newJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        DefaultMatchingEngine engine = new DefaultMatchingEngine();

        var first = engine.match(left, right, MappingOverrides.EMPTY, MatchingPolicy.conservativeV1(),
                ProgressListener.NONE, CancellationToken.NONE);
        var second = engine.match(left, right, MappingOverrides.EMPTY, MatchingPolicy.conservativeV1(),
                ProgressListener.NONE, CancellationToken.NONE);

        assertThat(first.confirmedMappings()).containsEntry(EntityId.classId("old/Readable"), EntityId.classId("a/b"));
        assertThat(first.confirmed()).anyMatch(match -> match.left().name().equals("calculate")
                && match.right().name().equals("x"));
        assertThat(second.confirmed()).isEqualTo(first.confirmed());
    }

    @Test
    void retainsStableIdentityWhenMethodCodeChanges() throws Exception {
        Path oldJar = TestArtifacts.writeJar(temporaryDirectory.resolve("stable-old.jar"),
                Map.of("sample/Service.class", TestArtifacts.simpleClass("sample/Service", "value", 1, 1)), false);
        Path newJar = TestArtifacts.writeJar(temporaryDirectory.resolve("stable-new.jar"),
                Map.of("sample/Service.class", TestArtifacts.simpleClass("sample/Service", "value", 2, 20)), false);
        JarArtifactLoader loader = new JarArtifactLoader();
        var left = loader.load(oldJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);
        var right = loader.load(newJar, ScanOptions.productionDefaults(), ProgressListener.NONE, CancellationToken.NONE);

        var result = new DefaultMatchingEngine().match(left, right, MappingOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.confirmedMappings()).containsEntry(
                EntityId.classId("sample/Service"), EntityId.classId("sample/Service"));
        assertThat(result.confirmed()).anyMatch(match -> match.left().name().equals("value")
                && match.right().name().equals("value"));
    }

    @Test
    void autoConfirmsBidirectionallyUniquePerfectClassDespiteSmallRunnerUpMargin() {
        ClassModel leftClass = classModel("same/Name", List.of(), List.of(), "left-structure", "left-code");
        ClassModel perfect = classModel("same/Name", List.of(), List.of(), "different-structure", "different-code");
        ClassModel runnerUp = classModel("other/Name", List.of(), List.of(), "runner-structure", "runner-code");
        ArtifactSnapshot left = snapshot("left", Map.of(leftClass.internalName(), leftClass));
        ArtifactSnapshot right = snapshot("right", Map.of(
                perfect.internalName(), perfect,
                runnerUp.internalName(), runnerUp));

        var result = new DefaultMatchingEngine().match(left, right, MappingOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.confirmed()).anySatisfy(match -> {
            assertThat(match.left()).isEqualTo(leftClass.id());
            assertThat(match.right()).isEqualTo(perfect.id());
            assertThat(match.status()).isEqualTo(MatchStatus.EXACT);
        });
        assertThat(result.rankedCandidates().get(leftClass.id()))
                .extracting(match -> match.right().name())
                .containsExactly("same/Name", "other/Name");
    }

    @Test
    void handlesPerfectMemberMatchesWithoutArbitraryTargetCompetition() {
        FieldModel collisionA = field("collision", "La/A;", "shared");
        FieldModel collisionB = field("collision", "Lb/B;", "shared");
        FieldModel uniqueLeft = field("unique", "I", "unique");
        FieldModel collisionRight = field("collision", "Lc/C;", "shared");
        FieldModel uniqueRight = field("unique", "I", "unique");
        MethodModel methodLeft = method("work", "code", "structure");
        MethodModel methodPerfect = method("work", "code", "structure");
        MethodModel methodRunnerUp = method("x", "code", "structure");
        ClassModel leftClass = classModel("old/Owner", List.of(collisionA, collisionB, uniqueLeft),
                List.of(methodLeft), "left", "left");
        ClassModel rightClass = classModel("new/Owner", List.of(collisionRight, uniqueRight),
                List.of(methodPerfect, methodRunnerUp), "right", "right");
        ArtifactSnapshot left = snapshot("left-members", Map.of(leftClass.internalName(), leftClass));
        ArtifactSnapshot right = snapshot("right-members", Map.of(rightClass.internalName(), rightClass));
        MappingOverrides classLock = new MappingOverrides(Map.of(leftClass.id(), rightClass.id()));

        var result = new DefaultMatchingEngine().match(left, right, classLock,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        EntityId uniqueFieldId = EntityId.fieldId(leftClass.internalName(), "unique", "I");
        EntityId methodId = EntityId.methodId(leftClass.internalName(), "work", "()I");
        assertThat(result.confirmedMappings())
                .containsEntry(uniqueFieldId, EntityId.fieldId(rightClass.internalName(), "unique", "I"))
                .containsEntry(methodId, EntityId.methodId(rightClass.internalName(), "work", "()I"));
        assertThat(result.rankedCandidates().get(methodId)).hasSize(2);
        assertThat(result.confirmed()).noneMatch(match -> match.left().name().equals("collision"));
        assertThat(result.candidates().keySet())
                .contains(EntityId.fieldId(leftClass.internalName(), "collision", "La/A;"),
                        EntityId.fieldId(leftClass.internalName(), "collision", "Lb/B;"));
    }

    @Test
    void retainsLowConfidenceAndNeighborBucketClassesForInspectionWithoutAutoConfirming() {
        MethodModel leftMethod = method("left", "left-code", "left-structure");
        MethodModel rightMethod = method("right", "right-code", "right-structure");
        MethodModel extraMethod = method("extra", "extra-code", "extra-structure");
        ClassModel leftClass = classModel("old/Source", List.of(), List.of(leftMethod), "left", "left");
        ClassModel rightClass = classModel("new/Target", List.of(), List.of(rightMethod, extraMethod), "right", "right");
        ArtifactSnapshot left = snapshot("low-left", Map.of(leftClass.internalName(), leftClass));
        ArtifactSnapshot right = snapshot("low-right", Map.of(rightClass.internalName(), rightClass));

        var result = new DefaultMatchingEngine().match(left, right, ComparisonOverrides.EMPTY,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.rankedCandidates().get(leftClass.id()))
                .extracting(match -> match.right().name())
                .containsExactly(rightClass.internalName());
        assertThat(result.candidates()).doesNotContainKey(leftClass.id());
        assertThat(result.confirmedMappings()).doesNotContainKey(leftClass.id());
    }

    @Test
    void confirmedAdditionIsExcludedFromAutomaticAndRankedCandidates() {
        ClassModel leftClass = classModel("old/Source", List.of(), List.of(), "same-structure", "same-code");
        ClassModel rightClass = classModel("new/Target", List.of(), List.of(), "same-structure", "same-code");
        ArtifactSnapshot left = snapshot("excluded-left", Map.of(leftClass.internalName(), leftClass));
        ArtifactSnapshot right = snapshot("excluded-right", Map.of(rightClass.internalName(), rightClass));
        ComparisonOverrides overrides = new ComparisonOverrides(Map.of(), Set.of(), Set.of(rightClass.id()));

        var result = new DefaultMatchingEngine().match(left, right, overrides,
                MatchingPolicy.conservativeV1(), ProgressListener.NONE, CancellationToken.NONE);

        assertThat(result.confirmedMappings()).doesNotContainKey(leftClass.id());
        assertThat(result.rankedCandidates().getOrDefault(leftClass.id(), List.of())).isEmpty();
    }

    private static ArtifactSnapshot snapshot(String name, Map<String, ClassModel> classes) {
        return new ArtifactSnapshot(Path.of(name + ".jar"), name, 0, 0, classes.size(), 21,
                Map.of(), classes, Map.of());
    }

    private static ClassModel classModel(
            String name,
            List<FieldModel> fields,
            List<MethodModel> methods,
            String structuralFingerprint,
            String bytecodeFingerprint) {
        return new ClassModel(name, name + ".class", 0, 1, 65, null, "java/lang/Object",
                List.of(), List.of(), fields, methods, structuralFingerprint, bytecodeFingerprint);
    }

    private static FieldModel field(String name, String descriptor, String fingerprint) {
        return new FieldModel(name, descriptor, null, 1, null, List.of(), fingerprint);
    }

    private static MethodModel method(String name, String code, String structure) {
        return new MethodModel(name, "()I", null, 1, List.of(), List.of(), 2, code, structure);
    }
}
