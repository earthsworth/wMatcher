package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SideBySideTextPanelThemeTest {
    @AfterEach
    void restoreLightTheme() throws Exception {
        SwingUtilities.invokeAndWait(FlatLightLaf::setup);
    }

    @Test
    void followsFlatLafThemeWithoutLosingTextOrHighlights() throws Exception {
        AtomicReference<SideBySideTextPanel> panelReference = new AtomicReference<>();
        AtomicReference<Color> darkBackground = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FlatDarkLaf.setup();
            SideBySideTextPanel panel = new SideBySideTextPanel();
            panel.setTexts("same\nold", "same\nnew");
            panelReference.set(panel);
            darkBackground.set(panel.leftArea().getBackground());
        });

        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            SwingUtilities.updateComponentTreeUI(panelReference.get());
        });

        SideBySideTextPanel panel = panelReference.get();
        assertThat(luminance(darkBackground.get())).isLessThan(luminance(panel.leftArea().getBackground()));
        assertThat(panel.leftArea().getText()).isEqualTo("same\nold");
        assertThat(panel.rightArea().getText()).isEqualTo("same\nnew");
        assertThat(panel.leftArea().getHighlighter().getHighlights()).isNotEmpty();
        assertThat(panel.rightArea().getHighlighter().getHighlights()).isNotEmpty();
        assertThat(panel.leftMinimapForTesting().markerBinCountForTesting()).isPositive();
        assertThat(panel.rightMinimapForTesting().markerBinCountForTesting()).isPositive();
    }

    @Test
    void boundsLongDocumentsAndNavigatesThroughTheScrollModel() throws Exception {
        AtomicReference<SideBySideTextPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SideBySideTextPanel panel = new SideBySideTextPanel();
            String common = java.util.stream.IntStream.range(0, 5_000)
                    .mapToObj(index -> "line " + index)
                    .collect(java.util.stream.Collectors.joining("\n"));
            panel.setTexts(common + "\nold", common + "\nnew");
            EditorMinimap minimap = panel.leftMinimapForTesting();
            minimap.setSize(EditorMinimap.MINIMAP_WIDTH, 400);
            minimap.setScrollRangeForTesting(0, 100, 1_000);
            minimap.navigateForTesting(399);
            panelReference.set(panel);
        });

        SideBySideTextPanel panel = panelReference.get();
        assertThat(panel.leftMinimapForTesting().binCountForTesting()).isEqualTo(2_048);
        assertThat(panel.leftMinimapForTesting().scrollValueForTesting()).isGreaterThan(800);

        SwingUtilities.invokeAndWait(() -> panel.setMinimapVisible(false));
        assertThat(panel.leftMinimapForTesting().isVisible()).isFalse();
        assertThat(panel.rightMinimapForTesting().isVisible()).isFalse();
        assertThat(EditorMinimap.DIFF_RAIL_WIDTH).isEqualTo(8);
        assertThat(EditorMinimap.DIFF_MARKER_HEIGHT).isGreaterThanOrEqualTo(4);
        assertThat(EditorMinimap.DIFF_ANCHOR_HEIGHT).isGreaterThan(EditorMinimap.DIFF_MARKER_HEIGHT);
    }

    @Test
    void searchesOnlyTheChosenSideAndSupportsFindOptions() throws Exception {
        AtomicReference<SideBySideTextPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SideBySideTextPanel panel = new SideBySideTextPanel();
            panel.setTexts("Needle needle needles", "right needle");
            panel.openFindForTesting(true);
            panel.findFieldForTesting().setText("needle");
            panel.setFindOptionsForTesting(true, true, false);
            panel.findForTesting(true);
            panelReference.set(panel);
        });

        SideBySideTextPanel panel = panelReference.get();
        assertThat(panel.leftArea().getSelectedText()).isEqualTo("needle");
        assertThat(panel.rightArea().getSelectionStart()).isEqualTo(panel.rightArea().getSelectionEnd());
        assertThat(panel.findBarVisibleForTesting()).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            panel.openFindForTesting(false);
            panel.findFieldForTesting().setText("right\\s+needle");
            panel.setFindOptionsForTesting(false, false, true);
            panel.findForTesting(true);
        });
        assertThat(panel.rightArea().getSelectedText()).isEqualTo("right needle");

        SwingUtilities.invokeAndWait(() -> {
            panel.findFieldForTesting().setText("[");
            panel.setFindOptionsForTesting(false, false, true);
            panel.findForTesting(true);
            panel.findFieldForTesting().getActionMap().get("close-find").actionPerformed(null);
        });
        assertThat(panel.findBarVisibleForTesting()).isFalse();
    }

    @Test
    void revealsIndependentMemberRangesAfterLoadingText() throws Exception {
        AtomicReference<SideBySideTextPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SideBySideTextPanel panel = new SideBySideTextPanel();
            panel.setTexts("header\n  oldMethod()V\nfooter", "header\n\n  newMethod()V\nfooter",
                    MemberTextLocator.structure(org.earthsworth.wmatcher.core.model.EntityId.methodId(
                            "old/Owner", "oldMethod", "()V")),
                    MemberTextLocator.structure(org.earthsworth.wmatcher.core.model.EntityId.methodId(
                            "new/Owner", "newMethod", "()V")));
            panelReference.set(panel);
        });
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(panelReference.get().leftArea().getSelectedText()).contains("oldMethod()V");
        assertThat(panelReference.get().rightArea().getSelectedText()).contains("newMethod()V");
    }

    private static int luminance(Color color) {
        return color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114;
    }
}
