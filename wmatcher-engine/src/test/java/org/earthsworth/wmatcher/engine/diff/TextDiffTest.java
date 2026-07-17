package org.earthsworth.wmatcher.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextDiffTest {
    @Test
    void classifiesAddedRemovedModifiedAndAnchorMarkers() {
        var insertion = TextDiff.compare("first\nlast", "first\nadded\nlast");
        assertThat(insertion.rightMarkers())
                .anyMatch(marker -> marker.kind() == TextDiff.MarkerKind.ADDED && !marker.anchor());
        assertThat(insertion.leftMarkers())
                .anyMatch(marker -> marker.kind() == TextDiff.MarkerKind.ADDED && marker.anchor());

        var deletion = TextDiff.compare("first\nremoved\nlast", "first\nlast");
        assertThat(deletion.leftMarkers())
                .anyMatch(marker -> marker.kind() == TextDiff.MarkerKind.REMOVED && !marker.anchor());
        assertThat(deletion.rightMarkers())
                .anyMatch(marker -> marker.kind() == TextDiff.MarkerKind.REMOVED && marker.anchor());

        var modification = TextDiff.compare("old", "new");
        assertThat(modification.leftMarkers())
                .allMatch(marker -> marker.kind() == TextDiff.MarkerKind.MODIFIED);
        assertThat(modification.rightMarkers())
                .allMatch(marker -> marker.kind() == TextDiff.MarkerKind.MODIFIED);
    }
}
