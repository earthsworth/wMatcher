package org.earthsworth.wmatcher.core.model;

public record ScanOptions(
        int targetRelease,
        long maximumFileSize,
        int maximumEntries,
        long maximumExpandedBytes) {
    public static final long GIBIBYTE = 1_073_741_824L;

    public ScanOptions {
        if (targetRelease < 8 || maximumFileSize < 1 || maximumEntries < 1 || maximumExpandedBytes < 1) {
            throw new IllegalArgumentException("Invalid scan limits");
        }
    }

    public static ScanOptions productionDefaults() {
        return new ScanOptions(21, GIBIBYTE, 100_000, 8 * GIBIBYTE);
    }
}
