package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import org.earthsworth.wmatcher.engine.diff.TextDiff;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

final class EditorMinimap extends JComponent {
    static final int MINIMAP_WIDTH = 80;
    private static final int MAXIMUM_BINS = 2048;
    private final RSyntaxTextArea editor;
    private final JScrollBar verticalScrollBar;
    private float[] density = new float[] {0.0f};
    private float[] indentation = new float[] {0.0f};
    private byte[] markerKinds = new byte[] {0};
    private boolean[] anchors = new boolean[] {false};

    EditorMinimap(RSyntaxTextArea editor, JScrollPane scrollPane) {
        this.editor = editor;
        verticalScrollBar = scrollPane.getVerticalScrollBar();
        Dimension size = new Dimension(MINIMAP_WIDTH, 1);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(new Dimension(MINIMAP_WIDTH, Integer.MAX_VALUE));
        setToolTipText(text("detail.minimap"));
        verticalScrollBar.addAdjustmentListener(event -> repaint());
        MouseAdapter navigation = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { navigate(event.getY()); }
            @Override public void mouseDragged(MouseEvent event) { navigate(event.getY()); }
        };
        addMouseListener(navigation);
        addMouseMotionListener(navigation);
    }

    void setContent(String text, List<TextDiff.Marker> markers) {
        String[] lines = text == null ? new String[] {""} : text.split("\\R", -1);
        int lineCount = Math.max(1, lines.length);
        int binCount = Math.min(MAXIMUM_BINS, lineCount);
        density = new float[binCount];
        indentation = new float[binCount];
        Arrays.fill(indentation, 1.0f);
        markerKinds = new byte[binCount];
        anchors = new boolean[binCount];
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            int bin = bin(lineIndex, lineCount, binCount);
            int leading = leadingWhitespace(line);
            int visible = Math.max(0, line.length() - leading);
            float indentRatio = Math.min(0.72f, leading / 80.0f);
            float densityRatio = Math.min(1.0f, visible / 96.0f);
            density[bin] = Math.max(density[bin], densityRatio);
            indentation[bin] = Math.min(indentation[bin], indentRatio);
        }
        for (TextDiff.Marker marker : markers) {
            int line = Math.max(0, Math.min(lineCount - 1, marker.line()));
            int bin = bin(line, lineCount, binCount);
            byte kind = markerCode(marker.kind());
            if (priority(kind) >= priority(markerKinds[bin])) {
                markerKinds[bin] = kind;
            }
            anchors[bin] |= marker.anchor();
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(minimapBackground());
            copy.fillRect(0, 0, getWidth(), getHeight());
            int height = Math.max(1, getHeight());
            int usableWidth = Math.max(1, getWidth() - 8);
            for (int y = 0; y < height; y++) {
                int from = y * density.length / height;
                int to = Math.max(from + 1, (y + 1) * density.length / height);
                to = Math.min(to, density.length);
                float rowDensity = 0.0f;
                float rowIndent = 1.0f;
                byte rowMarker = 0;
                boolean rowAnchor = false;
                for (int index = from; index < to; index++) {
                    rowDensity = Math.max(rowDensity, density[index]);
                    rowIndent = Math.min(rowIndent, indentation[index]);
                    if (priority(markerKinds[index]) >= priority(rowMarker)) {
                        rowMarker = markerKinds[index];
                    }
                    rowAnchor |= anchors[index];
                }
                if (rowDensity > 0.0f) {
                    int start = 3 + Math.round(rowIndent * usableWidth);
                    int length = Math.max(2, Math.round(rowDensity * (usableWidth - start + 3)));
                    copy.setColor(codeColor());
                    copy.drawLine(start, y, Math.min(getWidth() - 5, start + length), y);
                }
                if (rowMarker != 0) {
                    copy.setColor(markerColor(rowMarker));
                    int markerHeight = rowAnchor ? 2 : 1;
                    copy.fillRect(getWidth() - 4, y, 4, markerHeight);
                }
            }
            paintViewport(copy, height);
        } finally {
            copy.dispose();
        }
    }

    private void paintViewport(Graphics2D graphics, int height) {
        int maximum = Math.max(1, verticalScrollBar.getMaximum());
        int extent = Math.max(1, verticalScrollBar.getVisibleAmount());
        int top = Math.round(verticalScrollBar.getValue() / (float) maximum * height);
        int viewportHeight = Math.max(8, Math.round(extent / (float) maximum * height));
        if (top + viewportHeight > height) {
            top = Math.max(0, height - viewportHeight);
        }
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) {
            accent = new Color(90, 140, 220);
        }
        graphics.setColor(withAlpha(accent, FlatLaf.isLafDark() ? 58 : 44));
        graphics.fillRect(0, top, getWidth() - 4, viewportHeight);
        graphics.setColor(withAlpha(accent, 150));
        graphics.drawRect(0, top, Math.max(0, getWidth() - 5), Math.max(0, viewportHeight - 1));
    }

    private void navigate(int y) {
        int available = Math.max(0, verticalScrollBar.getMaximum() - verticalScrollBar.getVisibleAmount());
        float ratio = Math.max(0.0f, Math.min(1.0f, y / (float) Math.max(1, getHeight())));
        int centered = Math.round(ratio * verticalScrollBar.getMaximum()
                - verticalScrollBar.getVisibleAmount() / 2.0f);
        verticalScrollBar.setValue(Math.max(0, Math.min(available, centered)));
    }

    int markerBinCountForTesting() {
        int count = 0;
        for (byte marker : markerKinds) {
            if (marker != 0) count++;
        }
        return count;
    }

    int binCountForTesting() {
        return density.length;
    }

    void navigateForTesting(int y) {
        navigate(y);
    }

    void setScrollRangeForTesting(int value, int extent, int maximum) {
        verticalScrollBar.setValues(value, extent, 0, maximum);
    }

    int scrollValueForTesting() {
        return verticalScrollBar.getValue();
    }

    private Color minimapBackground() {
        Color background = editor.getBackground();
        return FlatLaf.isLafDark() ? background.brighter() : background.darker();
    }

    private Color codeColor() {
        Color foreground = editor.getForeground();
        return withAlpha(foreground, FlatLaf.isLafDark() ? 105 : 80);
    }

    private static Color markerColor(byte marker) {
        return switch (marker) {
            case 1 -> new Color(65, 180, 105);
            case 2 -> new Color(220, 85, 85);
            default -> new Color(235, 165, 65);
        };
    }

    private static int leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return index;
    }

    private static int bin(int line, int lineCount, int binCount) {
        return Math.min(binCount - 1, line * binCount / lineCount);
    }

    private static byte markerCode(TextDiff.MarkerKind kind) {
        return switch (kind) {
            case ADDED -> 1;
            case REMOVED -> 2;
            case MODIFIED -> 3;
        };
    }

    private static int priority(byte marker) {
        return marker == 3 ? 3 : marker == 2 ? 2 : marker == 1 ? 1 : 0;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
