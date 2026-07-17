package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.junit.jupiter.api.Test;

class ManualMatchDialogTest {
    @Test
    void filtersBySimpleNameOwnerAndDescriptorWithoutCaseSensitivity() {
        var field = new ManualMatchDialog.Choice(
                EntityId.fieldId("pkg/Owner", "displayName", "Ljava/lang/String;"), null, false);
        var method = new ManualMatchDialog.Choice(
                EntityId.methodId("pkg/Service", "run", "(I)V"), EntityId.methodId("old/S", "x", "(I)V"), false);
        List<ManualMatchDialog.Choice> choices = List.of(field, method);

        assertThat(ManualMatchDialog.filterChoices(choices, "DISPLAY")).containsExactly(field);
        assertThat(ManualMatchDialog.filterChoices(choices, "service")).containsExactly(method);
        assertThat(ManualMatchDialog.filterChoices(choices, "(i)v")).containsExactly(method);
        assertThat(ManualMatchDialog.filterChoices(choices, "missing")).isEmpty();
    }
}
