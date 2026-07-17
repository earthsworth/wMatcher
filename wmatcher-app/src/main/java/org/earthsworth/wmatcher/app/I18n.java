package org.earthsworth.wmatcher.app;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class I18n {
    private static volatile ResourceBundle bundle = load(AppPreferences.locale());

    private I18n() { }

    public static String text(String key, Object... arguments) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException exception) {
            pattern = key;
        }
        return arguments.length == 0 ? pattern : MessageFormat.format(pattern, arguments);
    }

    public static void use(Locale locale) {
        AppPreferences.setLocale(locale);
        bundle = load(locale);
    }

    private static ResourceBundle load(Locale locale) {
        return ResourceBundle.getBundle("org.earthsworth.wmatcher.app.messages", locale);
    }
}
