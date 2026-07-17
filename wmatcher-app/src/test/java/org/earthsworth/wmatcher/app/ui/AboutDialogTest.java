package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.event.HyperlinkEvent;
import org.earthsworth.wmatcher.app.AppMetadata;
import org.junit.jupiter.api.Test;

class AboutDialogTest {
    @Test
    void repositoryHyperlinkInvokesBrowserCallback() {
        AtomicBoolean opened = new AtomicBoolean();
        var link = AboutDialog.repositoryLink(() -> opened.set(true));

        var event = new HyperlinkEvent(link, HyperlinkEvent.EventType.ACTIVATED, null,
                AppMetadata.GITHUB_URL);
        for (var listener : link.getHyperlinkListeners()) {
            listener.hyperlinkUpdate(event);
        }

        assertThat(opened).isTrue();
        assertThat(link.getText()).contains(AppMetadata.GITHUB_URL);
    }
}
