package org.earthsworth.wmatcher.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppPreferencesTest {
    private Preferences preferences;

    @BeforeEach
    void createPreferencesNode() {
        preferences = Preferences.userRoot().node("wmatcher-tests/" + UUID.randomUUID());
    }

    @AfterEach
    void removePreferencesNode() throws Exception {
        preferences.removeNode();
    }

    @Test
    void keepsTenMostRecentProjectsInMostRecentFirstOrder() {
        for (int index = 0; index < 12; index++) {
            AppPreferences.touchRecentProject(preferences, Path.of("project-" + index + ".wmatch"), index);
        }

        var projects = AppPreferences.readRecentProjects(preferences);

        assertThat(projects).hasSize(10);
        assertThat(projects.getFirst().path().getFileName().toString()).isEqualTo("project-11.wmatch");
        assertThat(projects.getLast().path().getFileName().toString()).isEqualTo("project-2.wmatch");
    }

    @Test
    void migratesLegacySingleProjectAndSupportsRemoval() {
        preferences.put("recentProject", Path.of("legacy.wmatch").toAbsolutePath().toString());

        var migrated = AppPreferences.readRecentProjects(preferences);
        AppPreferences.removeRecentProject(preferences, migrated.getFirst().path());

        assertThat(migrated).hasSize(1);
        assertThat(preferences.get("recentProject", "")).isEmpty();
        assertThat(AppPreferences.readRecentProjects(preferences)).isEmpty();
    }

    @Test
    void keepsMinimapVisibleByDefaultAndPersistsTheToggle() {
        assertThat(AppPreferences.minimapVisible(preferences)).isTrue();

        AppPreferences.setMinimapVisible(preferences, false);

        assertThat(AppPreferences.minimapVisible(preferences)).isFalse();
    }
}
