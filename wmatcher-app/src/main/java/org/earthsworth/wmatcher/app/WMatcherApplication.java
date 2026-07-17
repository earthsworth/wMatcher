package org.earthsworth.wmatcher.app;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class WMatcherApplication {
    private WMatcherApplication() { }

    public static void main(String[] arguments) {
        System.setProperty("wmatcher.log.dir", logDirectory().toString());
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        installTheme(AppPreferences.theme());
        SwingUtilities.invokeLater(() -> new MainFrame(new WorkspaceController()).setVisible(true));
    }

    public static void installTheme(String theme) {
        AppPreferences.setTheme(theme);
        boolean dark = theme.equals("dark") || theme.equals("system") && systemPrefersDark();
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
    }

    private static boolean systemPrefersDark() {
        Object value = UIManager.get("laf.dark");
        if (Boolean.TRUE.equals(value)) {
            return true;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return commandOutput("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").contains("0x0");
        }
        if (os.contains("mac")) {
            return commandOutput("defaults", "read", "-g", "AppleInterfaceStyle")
                    .toLowerCase(Locale.ROOT).contains("dark");
        }
        String gtkTheme = System.getenv("GTK_THEME");
        return gtkTheme != null && gtkTheme.toLowerCase(Locale.ROOT).contains("dark");
    }

    private static String commandOutput(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static Path logDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, "wMatcher", "logs");
        }
        return Path.of(System.getProperty("user.home"), ".local", "state", "wmatcher", "logs");
    }
}
