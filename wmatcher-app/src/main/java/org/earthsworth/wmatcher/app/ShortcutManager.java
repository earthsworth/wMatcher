package org.earthsworth.wmatcher.app;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.KeyStroke;

public final class ShortcutManager {
    private static final String PREFIX = "shortcut.";

    private ShortcutManager() { }

    public static KeyStroke stroke(ShortcutId id) {
        String stored = AppPreferences.shortcut(id.name());
        if (stored.isBlank()) return id.defaultStroke();
        KeyStroke parsed = KeyStroke.getKeyStroke(stored);
        return parsed == null ? id.defaultStroke() : parsed;
    }

    public static Map<ShortcutId, KeyStroke> load() {
        Map<ShortcutId, KeyStroke> result = new EnumMap<>(ShortcutId.class);
        for (ShortcutId id : ShortcutId.values()) result.put(id, stroke(id));
        return result;
    }

    public static String conflict(ShortcutId id, KeyStroke stroke, Map<ShortcutId, KeyStroke> values) {
        if (stroke == null) return "";
        for (Map.Entry<ShortcutId, KeyStroke> entry : values.entrySet()) {
            if (entry.getKey() != id && entry.getKey().scope() == id.scope() && stroke.equals(entry.getValue())) {
                return entry.getKey().name();
            }
        }
        return "";
    }

    public static void save(Map<ShortcutId, KeyStroke> values) {
        for (ShortcutId id : ShortcutId.values()) {
            KeyStroke stroke = values.get(id);
            if (stroke == null || stroke.equals(id.defaultStroke())) {
                AppPreferences.clearShortcut(id.name());
            } else {
                AppPreferences.setShortcut(id.name(), stroke.toString());
            }
        }
    }

    public static void reset() {
        for (ShortcutId id : ShortcutId.values()) AppPreferences.clearShortcut(id.name());
    }
}
