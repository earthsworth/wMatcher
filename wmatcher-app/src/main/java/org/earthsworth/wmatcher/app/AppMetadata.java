package org.earthsworth.wmatcher.app;

public final class AppMetadata {
    public static final String GITHUB_URL = "https://github.com/earthsworth/wMatcher";

    private AppMetadata() { }

    public static String version() {
        return normalizedVersion(AppMetadata.class.getPackage().getImplementationVersion());
    }

    static String normalizedVersion(String implementationVersion) {
        return implementationVersion == null || implementationVersion.isBlank()
                ? "dev" : implementationVersion;
    }
}
