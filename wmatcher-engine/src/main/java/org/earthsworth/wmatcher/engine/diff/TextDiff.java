package org.earthsworth.wmatcher.engine.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
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
        List<Marker> leftMarkers = new ArrayList<>();
        List<Marker> rightMarkers = new ArrayList<>();
        List<AbstractDelta<String>> deltas = new ArrayList<>(DiffUtils.diff(leftLines, rightLines).getDeltas());
        for (AbstractDelta<String> delta : deltas) {
            int leftPosition = delta.getSource().getPosition();
            int rightPosition = delta.getTarget().getPosition();
            int leftSize = delta.getSource().size();
            int rightSize = delta.getTarget().size();
            addRange(changedLeft, leftPosition, leftSize);
            addRange(changedRight, rightPosition, rightSize);
            if (delta.getType() == DeltaType.INSERT) {
                leftMarkers.add(anchor(leftPosition, leftLines.size(), MarkerKind.ADDED));
                addMarkers(rightMarkers, rightPosition, rightSize, MarkerKind.ADDED);
            } else if (delta.getType() == DeltaType.DELETE) {
                addMarkers(leftMarkers, leftPosition, leftSize, MarkerKind.REMOVED);
                rightMarkers.add(anchor(rightPosition, rightLines.size(), MarkerKind.REMOVED));
            } else {
                addMarkers(leftMarkers, leftPosition, leftSize, MarkerKind.MODIFIED);
                addMarkers(rightMarkers, rightPosition, rightSize, MarkerKind.MODIFIED);
            }
        }
        return new LineChanges(changedLeft, changedRight, leftMarkers, rightMarkers);
    }

    private static void addRange(Set<Integer> target, int position, int size) {
        for (int index = 0; index < size; index++) {
            target.add(position + index);
        }
    }

    private static void addMarkers(List<Marker> target, int position, int size, MarkerKind kind) {
        for (int index = 0; index < size; index++) {
            target.add(new Marker(position + index, kind, false));
        }
    }

    private static Marker anchor(int position, int lineCount, MarkerKind kind) {
        int line = lineCount == 0 ? 0 : Math.min(Math.max(0, position), lineCount - 1);
        return new Marker(line, kind, true);
    }

    public record LineChanges(
            Set<Integer> leftLines,
            Set<Integer> rightLines,
            List<Marker> leftMarkers,
            List<Marker> rightMarkers) {
        public LineChanges {
            leftLines = Set.copyOf(leftLines);
            rightLines = Set.copyOf(rightLines);
            leftMarkers = List.copyOf(leftMarkers);
            rightMarkers = List.copyOf(rightMarkers);
        }

        public LineChanges(Set<Integer> leftLines, Set<Integer> rightLines) {
            this(leftLines, rightLines, List.of(), List.of());
        }
    }

    public record Marker(int line, MarkerKind kind, boolean anchor) { }

    public enum MarkerKind { ADDED, REMOVED, MODIFIED }
}
