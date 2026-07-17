package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import com.formdev.flatlaf.FlatLaf;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import org.earthsworth.wmatcher.engine.diff.TextDiff;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;
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
    private final AtomicBoolean synchronizing = new AtomicBoolean();
    private final JPanel findBar = new JPanel(new BorderLayout(8, 0));
    private final JTextField findField = new JTextField(24);
    private final JLabel findSide = new JLabel();
    private final JLabel findStatus = new JLabel();
    private final JCheckBox matchCase = new JCheckBox(text("find.matchCase"));
    private final JCheckBox wholeWord = new JCheckBox(text("find.wholeWord"));
    private final JCheckBox regularExpression = new JCheckBox(text("find.regex"));
    private RSyntaxTextArea activeSearchArea;
    private TextDiff.LineChanges lineChanges = new TextDiff.LineChanges(Set.of(), Set.of());
    private TextRange leftNavigationRange;
    private TextRange rightNavigationRange;

    public SideBySideTextPanel() {
        super(new BorderLayout());
        JPanel editors = new JPanel(new GridLayout(1, 2, 1, 0));
        editors.add(editorSide(leftScroll, leftMinimap));
        editors.add(editorSide(rightScroll, rightMinimap));
        add(findBar(), BorderLayout.NORTH);
        add(editors, BorderLayout.CENTER);
        findBar.setVisible(false);
        leftScroll.getVerticalScrollBar().addAdjustmentListener(event -> sync(leftScroll, rightScroll));
        rightScroll.getVerticalScrollBar().addAdjustmentListener(event -> sync(rightScroll, leftScroll));
        installEditorActions(left);
        installEditorActions(right);
        applyEditorTheme();
    }

    public void setSyntaxStyle(String style) {
        left.setSyntaxEditingStyle(style);
        right.setSyntaxEditingStyle(style);
    }

    public void setTexts(String leftText, String rightText) {
        setTexts(leftText, rightText, null, null);
    }

    public void setTexts(
            String leftText,
            String rightText,
            TextLocator leftLocator,
            TextLocator rightLocator) {
        left.setText(leftText == null ? "" : leftText);
        right.setText(rightText == null ? "" : rightText);
        left.setCaretPosition(0);
        right.setCaretPosition(0);
        leftNavigationRange = locate(leftLocator, left.getText());
        rightNavigationRange = locate(rightLocator, right.getText());
        lineChanges = TextDiff.compare(left.getText(), right.getText());
        applyHighlights();
        leftMinimap.setContent(left.getText(), lineChanges.leftMarkers());
        rightMinimap.setContent(right.getText(), lineChanges.rightMarkers());
        refreshFindHighlights();
        revealNavigationRanges();
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
            refreshFindHighlights();
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

    JTextField findFieldForTesting() {
        return findField;
    }

    void openFindForTesting(boolean leftSide) {
        openFind(leftSide ? left : right);
    }

    void findForTesting(boolean forward) {
        find(forward);
    }

    void setFindOptionsForTesting(boolean caseSensitive, boolean whole, boolean regex) {
        matchCase.setSelected(caseSensitive);
        wholeWord.setSelected(whole);
        regularExpression.setSelected(regex);
        refreshFindHighlights();
    }

    boolean findBarVisibleForTesting() {
        return findBar.isVisible();
    }

    private JPanel findBar() {
        findBar.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        JPanel query = new JPanel(new BorderLayout(6, 0));
        findField.putClientProperty("JTextField.placeholderText", text("find.placeholder"));
        query.add(findSide, BorderLayout.WEST);
        query.add(findField, BorderLayout.CENTER);
        query.add(findStatus, BorderLayout.EAST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton previous = new JButton("↑");
        previous.setToolTipText(text("find.previous"));
        previous.addActionListener(event -> find(false));
        JButton next = new JButton("↓");
        next.setToolTipText(text("find.next"));
        next.addActionListener(event -> find(true));
        JButton close = new JButton("×");
        close.setToolTipText(text("find.close"));
        close.addActionListener(event -> closeFind());
        matchCase.addActionListener(event -> refreshFindHighlights());
        wholeWord.addActionListener(event -> refreshFindHighlights());
        regularExpression.addActionListener(event -> refreshFindHighlights());
        actions.add(matchCase);
        actions.add(wholeWord);
        actions.add(regularExpression);
        actions.add(previous);
        actions.add(next);
        actions.add(close);
        findBar.add(query, BorderLayout.CENTER);
        findBar.add(actions, BorderLayout.EAST);
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshFindHighlights(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshFindHighlights(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshFindHighlights(); }
        });
        installFindFieldActions();
        return findBar;
    }

    private void installFindFieldActions() {
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find-next");
        findField.getActionMap().put("find-next", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { find(true); }
        });
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                "find-previous");
        findField.getActionMap().put("find-previous", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { find(false); }
        });
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-find");
        findField.getActionMap().put("close-find", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { closeFind(); }
        });
    }

    private void installEditorActions(RSyntaxTextArea area) {
        area.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                activeSearchArea = area;
                updateFindSide();
                if (findBar.isVisible()) refreshFindHighlights();
            }
        });
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "open-find");
        area.getActionMap().put("open-find", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openFind(area); }
        });
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "find-next");
        area.getActionMap().put("find-next", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { find(true); }
        });
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "find-previous");
        area.getActionMap().put("find-previous", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { find(false); }
        });
    }

    private void openFind(RSyntaxTextArea area) {
        activeSearchArea = area;
        String selected = area.getSelectedText();
        if (selected != null && !selected.isBlank() && !selected.contains("\n")) {
            findField.setText(selected);
        }
        findBar.setVisible(true);
        updateFindSide();
        revalidate();
        refreshFindHighlights();
        SwingUtilities.invokeLater(() -> {
            findField.requestFocusInWindow();
            findField.selectAll();
        });
    }

    private void closeFind() {
        findBar.setVisible(false);
        left.clearMarkAllHighlights();
        right.clearMarkAllHighlights();
        findStatus.setText("");
        revalidate();
        RSyntaxTextArea target = activeSearchArea == null ? left : activeSearchArea;
        SwingUtilities.invokeLater(target::requestFocusInWindow);
    }

    private void refreshFindHighlights() {
        if (left == null || right == null) return;
        left.clearMarkAllHighlights();
        right.clearMarkAllHighlights();
        if (findBar == null || !findBar.isVisible() || activeSearchArea == null || findField.getText().isEmpty()) {
            if (findStatus != null) findStatus.setText("");
            return;
        }
        try {
            SearchResult result = SearchEngine.markAll(activeSearchArea, searchContext(true));
            findStatus.setText(result.getMarkedCount() == 0
                    ? text("find.noMatches") : text("find.matches", result.getMarkedCount()));
        } catch (RuntimeException exception) {
            activeSearchArea.clearMarkAllHighlights();
            findStatus.setText(text("find.noMatches"));
        }
    }

    private void find(boolean forward) {
        if (activeSearchArea == null) activeSearchArea = left;
        if (!findBar.isVisible()) {
            openFind(activeSearchArea);
            return;
        }
        if (findField.getText().isEmpty()) return;
        try {
            SearchContext context = searchContext(forward);
            SearchResult marked = SearchEngine.markAll(activeSearchArea, context);
            SearchResult result = SearchEngine.find(activeSearchArea, context);
            findStatus.setText(result.wasFound()
                    ? text("find.matches", Math.max(marked.getMarkedCount(), 1)) : text("find.noMatches"));
        } catch (RuntimeException exception) {
            activeSearchArea.clearMarkAllHighlights();
            findStatus.setText(text("find.noMatches"));
        }
    }

    private SearchContext searchContext(boolean forward) {
        SearchContext context = new SearchContext(findField.getText());
        context.setSearchForward(forward);
        context.setSearchWrap(true);
        context.setMatchCase(matchCase.isSelected());
        context.setWholeWord(wholeWord.isSelected());
        context.setRegularExpression(regularExpression.isSelected());
        context.setMarkAll(true);
        return context;
    }

    private void updateFindSide() {
        if (findSide == null) return;
        findSide.setText(activeSearchArea == right ? text("find.right") : text("find.left"));
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
        highlightDifferences(left, lineChanges.leftLines());
        highlightDifferences(right, lineChanges.rightLines());
        highlightNavigation(left, leftNavigationRange);
        highlightNavigation(right, rightNavigationRange);
    }

    private void revealNavigationRanges() {
        SwingUtilities.invokeLater(() -> {
            synchronizing.set(true);
            try {
                reveal(left, leftScroll, leftNavigationRange);
                reveal(right, rightScroll, rightNavigationRange);
            } finally {
                synchronizing.set(false);
            }
        });
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

    private static void highlightDifferences(RSyntaxTextArea area, Set<Integer> lines) {
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

    private static void highlightNavigation(RSyntaxTextArea area, TextRange range) {
        if (range == null || range.start() >= area.getDocument().getLength()) return;
        Color accent = javax.swing.UIManager.getColor("Component.accentColor");
        if (accent == null) accent = new Color(80, 135, 220);
        Color color = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                FlatLaf.isLafDark() ? 110 : 80);
        try {
            area.getHighlighter().addHighlight(range.start(), Math.min(range.end(), area.getDocument().getLength()),
                    new DefaultHighlighter.DefaultHighlightPainter(color));
        } catch (BadLocationException ignored) {
            // Locator ranges are validated against the current text before they are stored.
        }
    }

    private static void reveal(RSyntaxTextArea area, JScrollPane scrollPane, TextRange range) {
        if (range == null || range.start() >= area.getDocument().getLength()) return;
        try {
            int start = range.start();
            int end = Math.min(range.end(), area.getDocument().getLength());
            area.setCaretPosition(start);
            area.moveCaretPosition(end);
            Rectangle2D rectangle = area.modelToView2D(start);
            if (rectangle != null) {
                int y = Math.max(0, (int) rectangle.getY() - scrollPane.getViewport().getExtentSize().height / 2);
                Point current = scrollPane.getViewport().getViewPosition();
                scrollPane.getViewport().setViewPosition(new Point(current.x, y));
            }
        } catch (BadLocationException ignored) {
            // The selected entity may disappear if a newer async document replaces this text.
        }
    }

    private static TextRange locate(TextLocator locator, String text) {
        if (locator == null) return null;
        TextRange range = locator.locate(text);
        if (range == null || range.start() > text.length()) return null;
        return new TextRange(range.start(), Math.min(range.end(), text.length()));
    }

    private void sync(JScrollPane source, JScrollPane target) {
        if (!synchronizing.compareAndSet(false, true)) return;
        try {
            target.getVerticalScrollBar().setValue(source.getVerticalScrollBar().getValue());
        } finally {
            synchronizing.set(false);
        }
    }
}
