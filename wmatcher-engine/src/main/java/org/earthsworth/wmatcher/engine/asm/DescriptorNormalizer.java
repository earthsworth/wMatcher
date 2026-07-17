package org.earthsworth.wmatcher.engine.asm;

import org.objectweb.asm.Type;

final class DescriptorNormalizer {
    private DescriptorNormalizer() { }

    static String normalize(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return "";
        }
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            StringBuilder result = new StringBuilder("(");
            for (Type argument : type.getArgumentTypes()) {
                result.append(normalizeType(argument));
            }
            return result.append(')').append(normalizeType(type.getReturnType())).toString();
        }
        return normalizeType(type);
    }

    private static String normalizeType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "V";
            case Type.BOOLEAN -> "Z";
            case Type.CHAR -> "C";
            case Type.BYTE -> "B";
            case Type.SHORT -> "S";
            case Type.INT -> "I";
            case Type.FLOAT -> "F";
            case Type.LONG -> "J";
            case Type.DOUBLE -> "D";
            case Type.ARRAY -> "[".repeat(type.getDimensions()) + normalizeType(type.getElementType());
            case Type.OBJECT -> "L;";
            default -> throw new IllegalArgumentException("Unsupported descriptor type " + type);
        };
    }
}
