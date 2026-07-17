package org.earthsworth.wmatcher.engine.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class TestArtifacts {
    private TestArtifacts() { }

    public static byte[] simpleClass(String internalName, String methodName, int returned, int lineNumber) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "value", "I", null, returned).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        method.visitCode();
        Label start = new Label();
        method.visitLabel(start);
        method.visitLineNumber(lineNumber, start);
        method.visitLdcInsn(returned);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static Path writeJar(Path path, Map<String, byte[]> entries, boolean multiRelease) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
