package org.earthsworth.wmatcher.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class WorkspaceControllerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void comparesTwoJarsThroughAsynchronousApplicationWorkflow() throws Exception {
        Path left = jar("old.jar", "example/Old", "read");
        Path right = jar("new.jar", "a/b", "x");
        WorkspaceController controller = new WorkspaceController();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<WorkspaceController.Workspace> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        controller.compare(WorkspaceController.CompareRequest.fresh(left, right, 21, List.of(), List.of()),
                ignored -> { }, workspace -> {
                    result.set(workspace);
                    completed.countDown();
                }, throwable -> {
                    failure.set(throwable);
                    completed.countDown();
                });

        assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().matches().confirmed()).isNotEmpty();
        assertThat(result.get().differences().nodes()).isNotEmpty();
        controller.close();
    }

    @Test
    void tracksFreshModifiedSavedAndReopenedDocumentState() throws Exception {
        Path left = jar("state-old.jar", "example/Old", "read");
        Path right = jar("state-new.jar", "a/b", "x");
        Path project = temporaryDirectory.resolve("state.wmatch");
        WorkspaceController controller = new WorkspaceController();

        WorkspaceController.Workspace compared = compare(controller, left, right);
        assertThat(controller.hasUnsavedChanges()).isTrue();

        awaitSave(controller, project);
        assertThat(controller.hasUnsavedChanges()).isFalse();

        CountDownLatch locked = new CountDownLatch(1);
        AtomicReference<Throwable> lockFailure = new AtomicReference<>();
        controller.lockMapping(compared.matches().confirmed().getFirst(), ignored -> locked.countDown(), throwable -> {
            lockFailure.set(throwable);
            locked.countDown();
        });
        assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(lockFailure.get()).isNull();
        assertThat(controller.hasUnsavedChanges()).isTrue();

        awaitSave(controller, project);
        assertThat(controller.hasUnsavedChanges()).isFalse();
        controller.close();

        WorkspaceController reopened = new WorkspaceController();
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<Throwable> openFailure = new AtomicReference<>();
        reopened.openProject(project, ignored -> { }, ignored -> opened.countDown(), throwable -> {
            openFailure.set(throwable);
            opened.countDown();
        });
        assertThat(opened.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(openFailure.get()).isNull();
        assertThat(reopened.hasUnsavedChanges()).isFalse();
        reopened.close();
    }

    private WorkspaceController.Workspace compare(WorkspaceController controller, Path left, Path right)
            throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<WorkspaceController.Workspace> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        controller.compare(WorkspaceController.CompareRequest.fresh(left, right, 21, List.of(), List.of()),
                ignored -> { }, workspace -> {
                    result.set(workspace);
                    completed.countDown();
                }, throwable -> {
                    failure.set(throwable);
                    completed.countDown();
                });
        assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        return result.get();
    }

    private static void awaitSave(WorkspaceController controller, Path project) throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        controller.saveProject(project, ProjectUiState.empty(), saved::countDown, throwable -> {
            failure.set(throwable);
            saved.countDown();
        });
        assertThat(saved.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
    }

    private Path jar(String fileName, String className, String methodName) throws Exception {
        Path path = temporaryDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(className + ".class"));
            output.write(classBytes(className, methodName));
            output.closeEntry();
        }
        return path;
    }

    private static byte[] classBytes(String className, String methodName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, 42);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
