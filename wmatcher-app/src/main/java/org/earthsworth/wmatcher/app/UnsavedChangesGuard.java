package org.earthsworth.wmatcher.app;

import java.util.Objects;
import java.util.function.Supplier;

final class UnsavedChangesGuard {
    void proceed(
            boolean hasUnsavedChanges,
            Supplier<Decision> prompt,
            SaveAction save,
            Runnable continuation) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(continuation, "continuation");
        if (!hasUnsavedChanges) {
            continuation.run();
            return;
        }
        switch (prompt.get()) {
            case SAVE -> save.start(continuation);
            case DISCARD -> continuation.run();
            case CANCEL -> { }
        }
    }

    enum Decision { SAVE, DISCARD, CANCEL }

    @FunctionalInterface
    interface SaveAction {
        void start(Runnable success);
    }
}
