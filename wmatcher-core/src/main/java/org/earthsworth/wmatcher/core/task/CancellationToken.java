package org.earthsworth.wmatcher.core.task;

import java.util.concurrent.CancellationException;

@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}
