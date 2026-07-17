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
    }

    private static int luminance(Color color) {
        return color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114;
    }
}
