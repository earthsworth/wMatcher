package org.earthsworth.wmatcher.app.ui;

import com.formdev.flatlaf.FlatLaf;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import org.earthsworth.wmatcher.engine.diff.TextDiff;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SideBySideTextPanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(SideBySideTextPanel.class);
    private static final String DARK_THEME = "/org/fife/ui/rsyntaxtextarea/themes/dark.xml";
    private static final String LIGHT_THEME = "/org/fife/ui/rsyntaxtextarea/themes/idea.xml";
    private final RSyntaxTextArea left = textArea();
    private final RSyntaxTextArea right = textArea();
    private final JScrollPane leftScroll = scrollPane(left);
    private final JScrollPane rightScroll = scrollPane(right);
    private final EditorMinimap leftMinimap = new EditorMinimap(left, leftScroll);
    private final EditorMinimap rightMinimap = new EditorMinimap(right, rightScroll);
    private TextDiff.LineChanges lineChanges = new TextDiff.LineChanges(Set.of(), Set.of());

    public SideBySideTextPanel() {
        super(new GridLayout(1, 2, 1, 0));
        add(editorSide(leftScroll, leftMinimap));
        add(editorSide(rightScroll, rightMinimap));
        AtomicBoolean synchronizing = new AtomicBoolean();
        leftScroll.getVerticalScrollBar().addAdjustmentListener(event -> sync(
                leftScroll, rightScroll, synchronizing));
        rightScroll.getVerticalScrollBar().addAdjustmentListener(event -> sync(
                rightScroll, leftScroll, synchronizing));
        applyEditorTheme();
    }

    public void setSyntaxStyle(String style) {
        left.setSyntaxEditingStyle(style);
        right.setSyntaxEditingStyle(style);
    }

    public void setTexts(String leftText, String rightText) {
        left.setText(leftText == null ? "" : leftText);
        right.setText(rightText == null ? "" : rightText);
        left.setCaretPosition(0);
        right.setCaretPosition(0);
        lineChanges = TextDiff.compare(left.getText(), right.getText());
        applyHighlights();
        leftMinimap.setContent(left.getText(), lineChanges.leftMarkers());
        rightMinimap.setContent(right.getText(), lineChanges.rightMarkers());
    }

    public void setMinimapVisible(boolean visible) {
        leftMinimap.setVisible(visible);
        rightMinimap.setVisible(visible);
        revalidate();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (left != null && right != null) {
            applyEditorTheme();
            applyHighlights();
            if (leftMinimap != null && rightMinimap != null) {
                leftMinimap.repaint();
                rightMinimap.repaint();
            }
        }
    }

    RSyntaxTextArea leftArea() {
        return left;
    }

    RSyntaxTextArea rightArea() {
        return right;
    }

    EditorMinimap leftMinimapForTesting() {
        return leftMinimap;
    }

    EditorMinimap rightMinimapForTesting() {
        return rightMinimap;
    }

    private void applyEditorTheme() {
        String resource = FlatLaf.isLafDark() ? DARK_THEME : LIGHT_THEME;
        try (InputStream input = SideBySideTextPanel.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing RSyntaxTextArea theme: " + resource);
            }
            Theme theme = Theme.load(input);
            theme.apply(left);
            theme.apply(right);
            java.awt.Font font = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13);
            left.setFont(font);
            right.setFont(font);
        } catch (IOException exception) {
            LOGGER.warn("Unable to apply editor theme {}", resource, exception);
        }
    }

    private void applyHighlights() {
        left.getHighlighter().removeAllHighlights();
        right.getHighlighter().removeAllHighlights();
        highlight(left, lineChanges.leftLines());
        highlight(right, lineChanges.rightLines());
    }

    public void setLoading(String message) {
        setTexts(message, message);
    }

    private static RSyntaxTextArea textArea() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setEditable(false);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        return area;
    }

    private static JScrollPane scrollPane(RSyntaxTextArea area) {
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    private static JPanel editorSide(JScrollPane scrollPane, EditorMinimap minimap) {
        JPanel side = new JPanel(new BorderLayout());
        side.add(scrollPane, BorderLayout.CENTER);
        side.add(minimap, BorderLayout.EAST);
        return side;
    }

    private static void highlight(RSyntaxTextArea area, Set<Integer> lines) {
        Color color = FlatLaf.isLafDark()
                ? new Color(255, 183, 77, 76)
                : new Color(255, 196, 64, 62);
        DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(color);
        for (int line : lines) {
            try {
                int start = area.getLineStartOffset(line);
                int end = area.getLineEndOffset(line);
                area.getHighlighter().addHighlight(start, end, painter);
            } catch (BadLocationException ignored) {
                // A trailing empty line can disappear when the text component normalizes its document.
            }
        }
    }

    private static void sync(JScrollPane source, JScrollPane target, AtomicBoolean synchronizing) {
        if (!synchronizing.compareAndSet(false, true)) {
            return;
        }
        try {
            target.getVerticalScrollBar().setValue(source.getVerticalScrollBar().getValue());
        } finally {
            synchronizing.set(false);
        }
    }
}
