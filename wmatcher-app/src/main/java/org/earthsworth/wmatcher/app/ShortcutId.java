package org.earthsworth.wmatcher.app;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;

public enum ShortcutId {
    NEW_PROJECT("shortcut.newProject", ShortcutScope.GLOBAL, KeyEvent.VK_N, primary()),
    OPEN_PROJECT("shortcut.openProject", ShortcutScope.GLOBAL, KeyEvent.VK_O, primary()),
    SAVE_PROJECT("shortcut.saveProject", ShortcutScope.GLOBAL, KeyEvent.VK_S, primary()),
    SAVE_AS("shortcut.saveAs", ShortcutScope.GLOBAL, KeyEvent.VK_S, primary() | InputEvent.SHIFT_DOWN_MASK),
    UNDO("shortcut.undo", ShortcutScope.GLOBAL, KeyEvent.VK_Z, primary()),
    REDO("shortcut.redo", ShortcutScope.GLOBAL, KeyEvent.VK_Z, primary() | InputEvent.SHIFT_DOWN_MASK),
    GLOBAL_SEARCH("shortcut.globalSearch", ShortcutScope.GLOBAL, KeyEvent.VK_G, primary()),
    FOCUS_TREE("shortcut.focusTree", ShortcutScope.WORKSPACE, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK),
    TAB_OVERVIEW("shortcut.tabOverview", ShortcutScope.WORKSPACE, KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK),
    TAB_CANDIDATES("shortcut.tabCandidates", ShortcutScope.WORKSPACE, KeyEvent.VK_3, InputEvent.ALT_DOWN_MASK),
    TAB_STRUCTURE("shortcut.tabStructure", ShortcutScope.WORKSPACE, KeyEvent.VK_4, InputEvent.ALT_DOWN_MASK),
    TAB_BYTECODE("shortcut.tabBytecode", ShortcutScope.WORKSPACE, KeyEvent.VK_5, InputEvent.ALT_DOWN_MASK),
    TAB_SOURCE("shortcut.tabSource", ShortcutScope.WORKSPACE, KeyEvent.VK_6, InputEvent.ALT_DOWN_MASK),
    FIND("shortcut.find", ShortcutScope.EDITOR, KeyEvent.VK_F, primary()),
    FIND_NEXT("shortcut.findNext", ShortcutScope.EDITOR, KeyEvent.VK_F3, 0),
    FIND_PREVIOUS("shortcut.findPrevious", ShortcutScope.EDITOR, KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK),
    FIND_FIELD_NEXT("shortcut.findFieldNext", ShortcutScope.EDITOR, KeyEvent.VK_ENTER, 0),
    FIND_FIELD_PREVIOUS("shortcut.findFieldPrevious", ShortcutScope.EDITOR, KeyEvent.VK_ENTER,
            InputEvent.SHIFT_DOWN_MASK),
    CLOSE_CHILD("shortcut.closeChild", ShortcutScope.DIALOG, KeyEvent.VK_ESCAPE, 0),
    ACCEPT("shortcut.accept", ShortcutScope.DIALOG, KeyEvent.VK_ENTER, 0),
    DIALOG_NEXT("shortcut.dialogNext", ShortcutScope.DIALOG, KeyEvent.VK_DOWN, 0),
    DIALOG_PREVIOUS("shortcut.dialogPrevious", ShortcutScope.DIALOG, KeyEvent.VK_UP, 0),
    RECENT_OPEN("shortcut.recentOpen", ShortcutScope.RECENT, KeyEvent.VK_ENTER, 0),
    RECENT_REMOVE("shortcut.recentRemove", ShortcutScope.RECENT, KeyEvent.VK_DELETE, 0);

    private final String labelKey;
    private final ShortcutScope scope;
    private final int keyCode;
    private final int modifiers;

    ShortcutId(String labelKey, ShortcutScope scope, int keyCode, int modifiers) {
        this.labelKey = labelKey;
        this.scope = scope;
        this.keyCode = keyCode;
        this.modifiers = modifiers;
    }

    public String labelKey() { return labelKey; }
    public ShortcutScope scope() { return scope; }

    public KeyStroke defaultStroke() {
        return KeyStroke.getKeyStroke(keyCode, modifiers);
    }

    private static int primary() {
        try {
            return java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException ignored) {
            return InputEvent.CTRL_DOWN_MASK;
        }
    }
}
