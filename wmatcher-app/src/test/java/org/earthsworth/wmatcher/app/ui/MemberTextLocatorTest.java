package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.earthsworth.wmatcher.core.model.EntityId;
import org.junit.jupiter.api.Test;

class MemberTextLocatorTest {
    @Test
    void locatesStructureAndAsmMembersByNameAndDescriptor() {
        EntityId method = EntityId.methodId("pkg/Owner", "work", "(I)Ljava/lang/String;");
        EntityId field = EntityId.fieldId("pkg/Owner", "value", "Ljava/lang/String;");
        String structure = "Methods\n  work(I)Ljava/lang/String;  [4 instructions]\n";
        String bytecode = "  // access flags 0x1\n  public work(I)Ljava/lang/String;\n";
        String fieldText = "  private Ljava/lang/String; value\n";

        assertThat(selected(structure, MemberTextLocator.structure(method))).contains("work(I)Ljava/lang/String;");
        assertThat(selected(bytecode, MemberTextLocator.bytecode(method))).contains("work(I)Ljava/lang/String;");
        assertThat(selected(fieldText, MemberTextLocator.bytecode(field))).contains("value");
    }

    @Test
    void distinguishesOverloadsAndSupportsConstructorsInitializersAndFields() {
        String source = """
                class Sample {
                    private String value;

                    Sample(int number) { }

                    public void work(int value) { }

                    public void work(String value) { }

                    static { register(); }
                }
                """;

        assertThat(selected(source, MemberTextLocator.source(
                EntityId.methodId("pkg/Sample", "work", "(Ljava/lang/String;)V"))))
                .contains("work(String value)");
        assertThat(selected(source, MemberTextLocator.source(
                EntityId.methodId("pkg/Sample", "<init>", "(I)V"))))
                .contains("Sample(int number)");
        assertThat(selected(source, MemberTextLocator.source(
                EntityId.methodId("pkg/Sample", "<clinit>", "()V"))))
                .contains("static");
        assertThat(selected(source, MemberTextLocator.source(
                EntityId.fieldId("pkg/Sample", "value", "Ljava/lang/String;"))))
                .isEqualTo("value");
    }

    private static String selected(String text, TextLocator locator) {
        TextRange range = locator.locate(text);
        assertThat(range).isNotNull();
        return text.substring(range.start(), range.end());
    }
}
