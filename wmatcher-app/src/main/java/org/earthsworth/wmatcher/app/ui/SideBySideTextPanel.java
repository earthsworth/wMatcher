package org.earthsworth.wmatcher.app.ui;

import com.formdev.flatlaf.FlatLaf;
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

    public SideBySideTextPanel() {
        super(new GridLayout(1, 2, 1, 0));
        JScrollPane leftScroll = new JScrollPane(left);
        JScrollPane rightScroll = new JScrollPane(right);
        leftScroll.setBorder(BorderFactory.createEmptyBorder());
        rightScroll.setBorder(BorderFactory.createEmptyBorder());
        add(leftScroll);
        add(rightScroll);
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
        refreshHighlights();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (left != null && right != null) {
            applyEditorTheme();
            refreshHighlights();
        }
    }

    RSyntaxTextArea leftArea() {
        return left;
    }

    RSyntaxTextArea rightArea() {
        return right;
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

    private void refreshHighlights() {
        left.getHighlighter().removeAllHighlights();
        right.getHighlighter().removeAllHighlights();
        TextDiff.LineChanges changes = TextDiff.compare(left.getText(), right.getText());
        highlight(left, changes.leftLines());
        highlight(right, changes.rightLines());
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
