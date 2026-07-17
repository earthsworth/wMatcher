package org.earthsworth.wmatcher.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;
import java.util.prefs.BackingStoreException;

public final class AppPreferences {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(WMatcherApplication.class);
    private static final String LANGUAGE = "language";
    private static final String THEME = "theme";
    private static final String MINIMAP_VISIBLE = "minimapVisible";
    private static final String RECENT_PROJECT = "recentProject";
    private static final String RECENT_PATH = "recent.path.";
    private static final String RECENT_TIME = "recent.time.";
    private static final int MAXIMUM_RECENT_PROJECTS = 10;

    private AppPreferences() { }

    public static Locale locale() {
        return locale(PREFERENCES, Locale.getDefault());
    }

    static Locale locale(Preferences preferences, Locale systemLocale) {
        String stored = preferences.get(LANGUAGE, "");
        if (stored.isBlank()) {
            return systemLocale.getLanguage().equals(Locale.CHINESE.getLanguage())
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        }
        Locale locale = Locale.forLanguageTag(stored);
        return locale.getLanguage().equals(Locale.CHINESE.getLanguage())
                ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
    }

    public static void setLocale(Locale locale) {
        setLocale(PREFERENCES, locale);
    }

    static void setLocale(Preferences preferences, Locale locale) {
        Locale supported = locale.getLanguage().equals(Locale.CHINESE.getLanguage())
                ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        preferences.put(LANGUAGE, supported.toLanguageTag());
        try {
            preferences.flush();
        } catch (BackingStoreException exception) {
            throw new IllegalStateException("Unable to save language preference", exception);
        }
    }

    public static String theme() {
        return PREFERENCES.get(THEME, "system");
    }

    public static void setTheme(String theme) {
        PREFERENCES.put(THEME, theme);
    }

    public static boolean minimapVisible() {
        return minimapVisible(PREFERENCES);
    }

    public static void setMinimapVisible(boolean visible) {
        setMinimapVisible(PREFERENCES, visible);
    }

    public static Optional<Path> recentProject() {
        return recentProjects().stream().findFirst().map(RecentProject::path);
    }

    public static void setRecentProject(Path path) {
        touchRecentProject(PREFERENCES, path, System.currentTimeMillis());
    }

    public static List<RecentProject> recentProjects() {
        return readRecentProjects(PREFERENCES);
    }

    public static void removeRecentProject(Path path) {
        removeRecentProject(PREFERENCES, path);
    }

    static List<RecentProject> readRecentProjects(Preferences preferences) {
        Map<Path, RecentProject> unique = new LinkedHashMap<>();
        for (int index = 0; index < MAXIMUM_RECENT_PROJECTS; index++) {
            String storedPath = preferences.get(RECENT_PATH + index, "");
            if (!storedPath.isBlank()) {
                try {
                    Path normalized = Path.of(storedPath).toAbsolutePath().normalize();
                    long opened = preferences.getLong(RECENT_TIME + index, 0L);
                    unique.putIfAbsent(normalized, new RecentProject(normalized, opened));
                } catch (RuntimeException ignored) {
                    // Ignore invalid values left by an older application or manual preference edits.
                }
            }
        }
        String legacy = preferences.get(RECENT_PROJECT, "");
        if (!legacy.isBlank()) {
            try {
                Path normalized = Path.of(legacy).toAbsolutePath().normalize();
                unique.putIfAbsent(normalized, new RecentProject(normalized, 0L));
                preferences.remove(RECENT_PROJECT);
                writeRecentProjects(preferences, unique.values().stream().toList());
            } catch (RuntimeException ignored) {
                preferences.remove(RECENT_PROJECT);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingLong(RecentProject::lastOpened).reversed())
                .limit(MAXIMUM_RECENT_PROJECTS)
                .toList();
    }

    static void touchRecentProject(Preferences preferences, Path path, long openedAt) {
        Path normalized = path.toAbsolutePath().normalize();
        List<RecentProject> recent = new ArrayList<>(readRecentProjects(preferences));
        recent.removeIf(project -> project.path().equals(normalized));
        recent.add(new RecentProject(normalized, openedAt));
        recent.sort(Comparator.comparingLong(RecentProject::lastOpened).reversed());
        writeRecentProjects(preferences, recent);
    }

    static void removeRecentProject(Preferences preferences, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        List<RecentProject> recent = readRecentProjects(preferences).stream()
                .filter(project -> !project.path().equals(normalized))
                .toList();
        writeRecentProjects(preferences, recent);
    }

    static boolean minimapVisible(Preferences preferences) {
        return preferences.getBoolean(MINIMAP_VISIBLE, true);
    }

    static void setMinimapVisible(Preferences preferences, boolean visible) {
        preferences.putBoolean(MINIMAP_VISIBLE, visible);
    }

    private static void writeRecentProjects(Preferences preferences, List<RecentProject> recent) {
        for (int index = 0; index < MAXIMUM_RECENT_PROJECTS; index++) {
            preferences.remove(RECENT_PATH + index);
            preferences.remove(RECENT_TIME + index);
        }
        for (int index = 0; index < Math.min(MAXIMUM_RECENT_PROJECTS, recent.size()); index++) {
            RecentProject project = recent.get(index);
            preferences.put(RECENT_PATH + index, project.path().toString());
            preferences.putLong(RECENT_TIME + index, project.lastOpened());
        }
    }

    public record RecentProject(Path path, long lastOpened) {
        public RecentProject {
            path = path.toAbsolutePath().normalize();
        }
    }
}
