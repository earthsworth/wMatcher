package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import org.earthsworth.wmatcher.core.model.MappingNamespaces;
import org.earthsworth.wmatcher.app.ShortcutId;
import org.earthsworth.wmatcher.app.ShortcutManager;

public final class NamespaceDialog extends JDialog {
    private final JComboBox<String> source;
    private final JComboBox<String> target;
    private MappingNamespaces result;

    private NamespaceDialog(Window owner, List<String> namespaces) {
        super(owner, text("dialog.mappingNamespaces"), Dialog.ModalityType.APPLICATION_MODAL);
        source = new JComboBox<>(namespaces.toArray(String[]::new));
        target = new JComboBox<>(namespaces.toArray(String[]::new));
        if (namespaces.size() > 1) target.setSelectedIndex(1);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 12));
        JPanel fields = new JPanel(new java.awt.GridLayout(2, 2, 8, 8));
        fields.add(new JLabel(text("dialog.mappingOldNamespace")));
        fields.add(source);
        fields.add(new JLabel(text("dialog.mappingNewNamespace")));
        fields.add(target);
        fields.setBorder(BorderFactory.createEmptyBorder(14, 14, 0, 14));
        add(fields, BorderLayout.CENTER);
        JButton cancel = new JButton(text("dialog.cancel"));
        JButton choose = new JButton(text("dialog.choose"));
        choose.addActionListener(event -> accept());
        cancel.addActionListener(event -> dispose());
        getRootPane().setDefaultButton(choose);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(choose);
        add(actions, BorderLayout.SOUTH);
        installEscapeAction();
        setPreferredSize(new Dimension(420, 170));
        pack();
        setLocationRelativeTo(owner);
    }

    public static MappingNamespaces showDialog(java.awt.Component parent, List<String> namespaces) {
        Window owner = javax.swing.SwingUtilities.getWindowAncestor(parent);
        NamespaceDialog dialog = new NamespaceDialog(owner, namespaces);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void accept() {
        String old = (String) source.getSelectedItem();
        String newer = (String) target.getSelectedItem();
        if (old != null && newer != null && !old.equals(newer)) {
            result = new MappingNamespaces(old, newer);
            dispose();
        }
    }

    private void installEscapeAction() {
        JRootPane root = getRootPane();
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW).put(ShortcutManager.stroke(ShortcutId.CLOSE_CHILD), "close");
        root.getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dispose(); }
        });
    }
}
