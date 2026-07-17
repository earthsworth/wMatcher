package org.earthsworth.wmatcher.core.task;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = (stage, completed, total) -> { };

    void onProgress(String stage, long completed, long total);
}
