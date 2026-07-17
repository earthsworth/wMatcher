package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.earthsworth.wmatcher.app.AppPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartPanelTest {
    @TempDir Path temporaryDirectory;

    @Test
    void opensExistingRecentProjectAndRemovesMissingEntryFromKeyboardActions() throws Exception {
        Path existing = Files.createFile(temporaryDirectory.resolve("existing.wmatch"));
        Path missing = temporaryDirectory.resolve("missing.wmatch");
        AtomicReference<Path> opened = new AtomicReference<>();
        AtomicReference<Path> removed = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            StartPanel panel = new StartPanel(() -> { }, () -> { }, opened::set, removed::set, List.of(
                    new AppPreferences.RecentProject(existing, 2),
                    new AppPreferences.RecentProject(missing, 1)));
            var list = panel.recentListForTesting();
            list.setSelectedIndex(0);
            list.getActionMap().get("open").actionPerformed(null);
            list.setSelectedIndex(1);
            list.getActionMap().get("open").actionPerformed(null);
            list.getActionMap().get("remove").actionPerformed(null);
        });

        assertThat(opened).hasValue(existing.toAbsolutePath().normalize());
        assertThat(removed).hasValue(missing.toAbsolutePath().normalize());
    }

    @Test
    void inlineRemoveHitTargetOnlyRemovesTheRecentEntry() throws Exception {
        Path project = Files.createFile(temporaryDirectory.resolve("kept.wmatch"));
        AtomicReference<Path> removed = new AtomicReference<>();
        AtomicReference<StartPanel> panelReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            StartPanel panel = new StartPanel(() -> { }, () -> { }, ignored -> { }, removed::set,
                    List.of(new AppPreferences.RecentProject(project, 1)));
            panelReference.set(panel);
            var list = panel.recentListForTesting();
            list.setSize(500, 58);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 490, 20, 1, false, MouseEvent.BUTTON1));
        });

        assertThat(removed).hasValue(project.toAbsolutePath().normalize());
        assertThat(project).exists();
        assertThat(panelReference.get().recentListForTesting().getModel().getSize()).isZero();
    }
}
