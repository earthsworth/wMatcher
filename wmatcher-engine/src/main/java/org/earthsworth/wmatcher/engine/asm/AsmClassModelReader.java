package org.earthsworth.wmatcher.engine.asm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.engine.util.Hashing;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class AsmClassModelReader {
    private static final int SEMANTIC_ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE
            | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_RECORD | Opcodes.ACC_SYNCHRONIZED
            | Opcodes.ACC_NATIVE | Opcodes.ACC_STRICT | Opcodes.ACC_VOLATILE | Opcodes.ACC_TRANSIENT;

    public ClassModel read(byte[] bytes, String entryName, int releaseVersion) {
        Accumulator accumulator = new Accumulator(entryName, releaseVersion);
        new ClassReader(bytes).accept(accumulator, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return accumulator.toModel();
    }

    private static final class Accumulator extends ClassVisitor {
        private final String entryName;
        private final int releaseVersion;
        private final List<String> interfaces = new ArrayList<>();
        private final List<String> annotations = new ArrayList<>();
        private final List<FieldModel> fields = new ArrayList<>();
        private final List<MethodModel> methods = new ArrayList<>();
        private String name;
        private String signature;
        private String superName;
        private int access;
        private int version;

        Accumulator(String entryName, int releaseVersion) {
            super(Opcodes.ASM9);
            this.entryName = entryName;
            this.releaseVersion = releaseVersion;
        }

        @Override
        public void visit(
                int classVersion,
                int classAccess,
                String className,
                String classSignature,
                String classSuperName,
                String[] classInterfaces) {
            version = classVersion;
            access = classAccess;
            name = className;
            signature = classSignature;
            superName = classSuperName;
            Collections.addAll(interfaces, classInterfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(descriptor + ':' + visible);
            return null;
        }

        @Override
        public FieldVisitor visitField(
                int fieldAccess,
                String fieldName,
                String descriptor,
                String fieldSignature,
                Object value) {
            List<String> fieldAnnotations = new ArrayList<>();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    fieldAnnotations.add(annotationDescriptor + ':' + visible);
                    return null;
                }

                @Override
                public void visitEnd() {
                    String valueToken = value == null ? "" : stableConstant(value);
                    String token = (fieldAccess & SEMANTIC_ACCESS_MASK) + "|"
                            + DescriptorNormalizer.normalize(descriptor) + '|' + valueToken + '|'
                            + annotationShape(fieldAnnotations);
                    fields.add(new FieldModel(
                            fieldName,
                            descriptor,
                            fieldSignature,
                            fieldAccess,
                            valueToken,
                            fieldAnnotations,
                            Hashing.sha256(token)));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int methodAccess,
                String methodName,
                String descriptor,
                String methodSignature,
                String[] exceptions) {
            return new CompactMethodVisitor(methodAccess, methodName, descriptor, methodSignature, exceptions, methods);
        }

        ClassModel toModel() {
            List<String> fieldTokens = fields.stream().map(FieldModel::fingerprint).sorted().toList();
            List<String> methodTokens = methods.stream().map(MethodModel::structuralFingerprint).sorted().toList();
            List<String> codeTokens = methods.stream().map(MethodModel::instructionFingerprint).sorted().toList();
            int kind = access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_RECORD);
            String structure = kind + "|" + interfaces.size() + '|' + annotationShape(annotations)
                    + '|' + String.join(",", fieldTokens) + '|' + String.join(",", methodTokens);
            return new ClassModel(
                    name,
                    entryName,
                    releaseVersion,
                    access,
                    version,
                    signature,
                    superName,
                    interfaces,
                    annotations,
                    fields,
                    methods,
                    Hashing.sha256(structure),
                    Hashing.sha256(String.join(",", codeTokens)));
        }
    }

    private static final class CompactMethodVisitor extends MethodVisitor {
        private final int access;
        private final String name;
        private final String descriptor;
        private final String signature;
        private final List<String> exceptions;
        private final List<String> annotations = new ArrayList<>();
        private final List<MethodModel> target;
        private final MessageDigest instructions = Hashing.sha256Digest();
        private int instructionCount;

        CompactMethodVisitor(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions,
                List<MethodModel> target) {
            super(Opcodes.ASM9);
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.signature = signature;
            this.exceptions = exceptions == null ? List.of() : List.of(exceptions);
            this.target = target;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
            annotations.add(annotationDescriptor + ':' + visible);
            return null;
        }

        @Override
        public void visitInsn(int opcode) {
            token("I:" + opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            token("N:" + opcode + ':' + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int variable) {
            token("V:" + opcode + ':' + variable);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            token("T:" + opcode);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
            token("F:" + opcode + ':' + DescriptorNormalizer.normalize(fieldDescriptor));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
            token("M:" + opcode + ':' + DescriptorNormalizer.normalize(methodDescriptor) + ':' + isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor, Handle bootstrap, Object... arguments) {
            token("D:" + DescriptorNormalizer.normalize(dynamicDescriptor) + ':' + bootstrap.getTag() + ':' + arguments.length);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            token("J:" + opcode);
        }

        @Override
        public void visitLdcInsn(Object value) {
            token("L:" + stableConstant(value));
        }

        @Override
        public void visitIincInsn(int variable, int increment) {
            token("C:" + variable + ':' + increment);
        }

        @Override
        public void visitTableSwitchInsn(int minimum, int maximum, Label defaultLabel, Label... labels) {
            token("S:T:" + minimum + ':' + maximum + ':' + labels.length);
        }

        @Override
        public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) {
            token("S:L:" + keys.length);
            for (int key : keys) {
                token(Integer.toString(key));
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
            token("A:" + DescriptorNormalizer.normalize(arrayDescriptor) + ':' + dimensions);
        }

        @Override
        public void visitEnd() {
            String instructionFingerprint = Hashing.hex(instructions.digest());
            String specialName = name.startsWith("<") ? name : "method";
            String structural = (access & SEMANTIC_ACCESS_MASK) + "|" + specialName + '|'
                    + DescriptorNormalizer.normalize(descriptor) + '|' + exceptions.size() + '|'
                    + annotationShape(annotations) + '|' + instructionCount;
            target.add(new MethodModel(
                    name,
                    descriptor,
                    signature,
                    access,
                    exceptions,
                    annotations,
                    instructionCount,
                    instructionFingerprint,
                    Hashing.sha256(structural)));
        }

        private void token(String value) {
            instructionCount++;
            instructions.update(value.getBytes(StandardCharsets.UTF_8));
            instructions.update((byte) 0);
        }
    }

    private static String annotationShape(List<String> descriptors) {
        return descriptors.stream()
                .map(value -> DescriptorNormalizer.normalize(value.substring(0, value.lastIndexOf(':'))))
                .sorted()
                .reduce((left, right) -> left + ',' + right)
                .orElse("");
    }

    private static String stableConstant(Object value) {
        if (value instanceof Type type) {
            return "type:" + DescriptorNormalizer.normalize(type.getDescriptor());
        }
        if (value instanceof Handle handle) {
            return "handle:" + handle.getTag() + ':' + DescriptorNormalizer.normalize(handle.getDesc());
        }
        if (value instanceof String text) {
            return "string:" + Hashing.sha256(text);
        }
        return value.getClass().getSimpleName() + ':' + value;
    }
}
