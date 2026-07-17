package org.earthsworth.wmatcher.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class AppMetadataTest {
    @Test
    void usesManifestVersionWithDevelopmentFallback() {
        assertThat(AppMetadata.normalizedVersion(null)).isEqualTo("dev");
        assertThat(AppMetadata.normalizedVersion("  ")).isEqualTo("dev");
        assertThat(AppMetadata.normalizedVersion("2.4.1")).isEqualTo("2.4.1");
        assertThat(AppMetadata.version()).isEqualTo("dev");
    }

    @Test
    void exposesRepositoryAndLocalizedVersionPlaceholder() {
        assertThat(AppMetadata.GITHUB_URL).isEqualTo("https://github.com/earthsworth/wMatcher");
        ResourceBundle english = ResourceBundle.getBundle(
                "org.earthsworth.wmatcher.app.messages", Locale.ENGLISH);
        ResourceBundle chinese = ResourceBundle.getBundle(
                "org.earthsworth.wmatcher.app.messages", Locale.SIMPLIFIED_CHINESE);

        assertThat(english.getString("dialog.about")).contains("{0}");
        assertThat(chinese.getString("dialog.about")).contains("{0}");
    }
}
