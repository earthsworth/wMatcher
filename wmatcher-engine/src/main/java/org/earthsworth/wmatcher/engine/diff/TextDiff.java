package org.earthsworth.wmatcher.engine.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TextDiff {
    private TextDiff() { }

    public static LineChanges compare(String left, String right) {
        List<String> leftLines = left.lines().toList();
        List<String> rightLines = right.lines().toList();
        Set<Integer> changedLeft = new HashSet<>();
        Set<Integer> changedRight = new HashSet<>();
        List<AbstractDelta<String>> deltas = new ArrayList<>(DiffUtils.diff(leftLines, rightLines).getDeltas());
        for (AbstractDelta<String> delta : deltas) {
            addRange(changedLeft, delta.getSource().getPosition(), delta.getSource().size());
            addRange(changedRight, delta.getTarget().getPosition(), delta.getTarget().size());
        }
        return new LineChanges(changedLeft, changedRight);
    }

    private static void addRange(Set<Integer> target, int position, int size) {
        for (int index = 0; index < size; index++) {
            target.add(position + index);
        }
    }

    public record LineChanges(Set<Integer> leftLines, Set<Integer> rightLines) {
        public LineChanges {
            leftLines = Set.copyOf(leftLines);
            rightLines = Set.copyOf(rightLines);
        }
    }
}
