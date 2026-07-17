package org.earthsworth.wmatcher.engine.asm;

import static org.assertj.core.api.Assertions.assertThat;

import org.earthsworth.wmatcher.engine.support.TestArtifacts;
import org.junit.jupiter.api.Test;

class AsmClassModelReaderTest {
    @Test
    void ignoresNamesAndDebugLinesInSemanticFingerprints() {
        AsmClassModelReader reader = new AsmClassModelReader();
        var oldModel = reader.read(TestArtifacts.simpleClass("old/Readable", "calculate", 7, 10), "old.class", 0);
        var newModel = reader.read(TestArtifacts.simpleClass("a/b", "x", 7, 999), "new.class", 0);

        assertThat(newModel.structuralFingerprint()).isEqualTo(oldModel.structuralFingerprint());
        assertThat(newModel.bytecodeFingerprint()).isEqualTo(oldModel.bytecodeFingerprint());
        assertThat(newModel.methods().stream().filter(method -> !method.name().equals("<init>"))
                .findFirst().orElseThrow().instructionFingerprint())
                .isEqualTo(oldModel.methods().stream().filter(method -> !method.name().equals("<init>"))
                        .findFirst().orElseThrow().instructionFingerprint());
    }

    @Test
    void detectsChangedInstructions() {
        AsmClassModelReader reader = new AsmClassModelReader();
        var left = reader.read(TestArtifacts.simpleClass("sample/A", "value", 7, 1), "a.class", 0);
        var right = reader.read(TestArtifacts.simpleClass("sample/A", "value", 8, 1), "b.class", 0);

        assertThat(right.bytecodeFingerprint()).isNotEqualTo(left.bytecodeFingerprint());
    }
}
