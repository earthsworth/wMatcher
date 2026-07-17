package org.earthsworth.wmatcher.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UnsavedChangesGuardTest {
    private final UnsavedChangesGuard guard = new UnsavedChangesGuard();

    @Test
    void continuesImmediatelyWhenDocumentIsClean() {
        AtomicInteger continuations = new AtomicInteger();

        guard.proceed(false, () -> UnsavedChangesGuard.Decision.CANCEL,
                ignored -> { }, continuations::incrementAndGet);

        assertThat(continuations).hasValue(1);
    }

    @Test
    void honorsDiscardAndCancelChoices() {
        AtomicInteger continuations = new AtomicInteger();
        guard.proceed(true, () -> UnsavedChangesGuard.Decision.CANCEL,
                ignored -> { }, continuations::incrementAndGet);
        assertThat(continuations).hasValue(0);

        guard.proceed(true, () -> UnsavedChangesGuard.Decision.DISCARD,
                ignored -> { }, continuations::incrementAndGet);
        assertThat(continuations).hasValue(1);
    }

    @Test
    void continuesAfterSaveReportsSuccessOnly() {
        AtomicInteger continuations = new AtomicInteger();
        AtomicReference<Runnable> saveSuccess = new AtomicReference<>();

        guard.proceed(true, () -> UnsavedChangesGuard.Decision.SAVE,
                saveSuccess::set, continuations::incrementAndGet);
        assertThat(continuations).hasValue(0);

        saveSuccess.get().run();
        assertThat(continuations).hasValue(1);
    }
}
