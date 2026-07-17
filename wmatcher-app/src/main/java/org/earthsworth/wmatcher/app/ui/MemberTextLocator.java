package org.earthsworth.wmatcher.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;

final class MemberTextLocator {
    private static final Pattern ACCESS_MODIFIER = Pattern.compile(
            "\\b(public|protected|private|static|final|abstract|synchronized|native|strictfp|transient|volatile)\\b");

    private MemberTextLocator() { }

    static TextLocator structure(EntityId id) {
        if (id == null || id.kind() == EntityKind.CLASS || id.kind() == EntityKind.RESOURCE) {
            return text -> null;
        }
        String needle = id.kind() == EntityKind.METHOD
                ? id.name() + id.descriptor()
                : id.name() + ' ' + id.descriptor();
        return text -> lineContaining(text, List.of(needle), id.name());
    }

    static TextLocator bytecode(EntityId id) {
        if (id == null || id.kind() == EntityKind.CLASS || id.kind() == EntityKind.RESOURCE) {
            return text -> null;
        }
        return text -> lineContaining(text, List.of(id.name(), id.descriptor()), id.name());
    }

    static TextLocator source(EntityId id) {
        if (id == null || id.kind() == EntityKind.CLASS || id.kind() == EntityKind.RESOURCE) {
            return text -> null;
        }
        return id.kind() == EntityKind.METHOD
                ? text -> locateSourceMethod(text, id)
                : text -> locateSourceField(text, id);
    }

    private static TextRange locateSourceMethod(String text, EntityId id) {
        if (id.name().equals("<clinit>")) {
            Matcher matcher = Pattern.compile("\\bstatic\\s*\\{").matcher(text);
            return matcher.find() ? lineRange(text, matcher.start()) : null;
        }
        String displayName = id.name().equals("<init>") ? simpleName(id.owner()) : id.name();
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(displayName) + "\\s*\\(");
        Matcher matcher = pattern.matcher(text);
        List<String> expectedTypes = methodParameterTypes(id.descriptor());
        TextRange best = null;
        int bestScore = Integer.MIN_VALUE;
        while (matcher.find()) {
            int open = text.indexOf('(', matcher.start());
            int close = matchingParenthesis(text, open);
            if (close < 0) {
                continue;
            }
            String parameters = text.substring(open + 1, close);
            int score = parameterCount(parameters) == expectedTypes.size() ? 20 : -20;
            String normalizedParameters = parameters.replace("...", "[]");
            for (String type : expectedTypes) {
                if (normalizedParameters.contains(type)) {
                    score += 4;
                }
            }
            int lineStart = lineStart(text, matcher.start());
            String prefix = text.substring(lineStart, matcher.start());
            if (ACCESS_MODIFIER.matcher(prefix).find()) {
                score += 8;
            }
            int previous = previousNonWhitespace(text, matcher.start() - 1);
            if (previous >= 0 && (text.charAt(previous) == '.' || startsWithWord(text, previous, "new"))) {
                score -= 30;
            }
            int lookAheadEnd = Math.min(text.length(), close + 160);
            String suffix = text.substring(close + 1, lookAheadEnd);
            if (suffix.contains("{") || suffix.stripLeading().startsWith("throws")) {
                score += 8;
            }
            if (score > bestScore) {
                bestScore = score;
                best = new TextRange(matcher.start(), close + 1);
            }
        }
        return best != null ? best : firstWord(text, displayName);
    }

    private static TextRange locateSourceField(String text, EntityId id) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(id.name()) + "\\b");
        Matcher matcher = pattern.matcher(text);
        String expectedType = fieldType(id.descriptor());
        TextRange best = null;
        int bestScore = Integer.MIN_VALUE;
        while (matcher.find()) {
            TextRange line = lineRange(text, matcher.start());
            String lineText = text.substring(line.start(), line.end());
            int score = 0;
            if (lineText.contains(";")) score += 8;
            if (ACCESS_MODIFIER.matcher(lineText).find()) score += 8;
            if (!expectedType.isBlank() && lineText.contains(expectedType)) score += 6;
            int previous = previousNonWhitespace(text, matcher.start() - 1);
            if (previous >= 0 && text.charAt(previous) == '.') score -= 20;
            int next = nextNonWhitespace(text, matcher.end());
            if (next >= 0 && text.charAt(next) == '(') score -= 20;
            if (score > bestScore) {
                bestScore = score;
                best = new TextRange(matcher.start(), matcher.end());
            }
        }
        return best;
    }

    private static TextRange lineContaining(String text, List<String> required, String fallback) {
        int offset = 0;
        for (String line : text.split("\\R", -1)) {
            boolean matches = required.stream().allMatch(line::contains);
            if (matches) {
                return new TextRange(offset, offset + line.length());
            }
            offset += line.length() + 1;
        }
        return firstWord(text, fallback);
    }

    private static TextRange firstWord(String text, String word) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text);
        return matcher.find() ? new TextRange(matcher.start(), matcher.end()) : null;
    }

    private static TextRange lineRange(String text, int position) {
        int start = lineStart(text, position);
        int end = text.indexOf('\n', position);
        return new TextRange(start, end < 0 ? text.length() : end);
    }

    private static int lineStart(String text, int position) {
        int newline = text.lastIndexOf('\n', Math.max(0, position - 1));
        return newline < 0 ? 0 : newline + 1;
    }

    private static int matchingParenthesis(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '(') depth++;
            if (current == ')' && --depth == 0) return index;
        }
        return -1;
    }

    private static int parameterCount(String parameters) {
        if (parameters.isBlank()) return 0;
        int count = 1;
        int angles = 0;
        int parentheses = 0;
        int brackets = 0;
        for (int index = 0; index < parameters.length(); index++) {
            char current = parameters.charAt(index);
            switch (current) {
                case '<' -> angles++;
                case '>' -> angles = Math.max(0, angles - 1);
                case '(' -> parentheses++;
                case ')' -> parentheses = Math.max(0, parentheses - 1);
                case '[' -> brackets++;
                case ']' -> brackets = Math.max(0, brackets - 1);
                case ',' -> {
                    if (angles == 0 && parentheses == 0 && brackets == 0) count++;
                }
                default -> { }
            }
        }
        return count;
    }

    private static List<String> methodParameterTypes(String descriptor) {
        List<String> result = new ArrayList<>();
        int index = descriptor.indexOf('(') + 1;
        int end = descriptor.indexOf(')');
        while (index > 0 && index < end) {
            ParsedType parsed = parseType(descriptor, index);
            result.add(parsed.name());
            index = parsed.next();
        }
        return result;
    }

    private static String fieldType(String descriptor) {
        return descriptor.isBlank() ? "" : parseType(descriptor, 0).name();
    }

    private static ParsedType parseType(String descriptor, int start) {
        int index = start;
        int arrays = 0;
        while (descriptor.charAt(index) == '[') {
            arrays++;
            index++;
        }
        char kind = descriptor.charAt(index);
        String name;
        int next;
        if (kind == 'L') {
            int semicolon = descriptor.indexOf(';', index);
            String internal = descriptor.substring(index + 1, semicolon);
            name = simpleName(internal).replace('$', '.');
            next = semicolon + 1;
        } else {
            name = switch (kind) {
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'D' -> "double";
                case 'F' -> "float";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'S' -> "short";
                case 'Z' -> "boolean";
                case 'V' -> "void";
                default -> "";
            };
            next = index + 1;
        }
        return new ParsedType(name + "[]".repeat(arrays), next);
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    private static int previousNonWhitespace(String text, int start) {
        for (int index = start; index >= 0; index--) {
            if (!Character.isWhitespace(text.charAt(index))) return index;
        }
        return -1;
    }

    private static int nextNonWhitespace(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) return index;
        }
        return -1;
    }

    private static boolean startsWithWord(String text, int end, String word) {
        int start = end - word.length() + 1;
        return start >= 0 && text.substring(start, end + 1).toLowerCase(Locale.ROOT).equals(word);
    }

    private record ParsedType(String name, int next) { }
}
