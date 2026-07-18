package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import org.earthsworth.wmatcher.app.I18n;
import org.earthsworth.wmatcher.app.ShortcutId;
import org.earthsworth.wmatcher.app.ShortcutManager;

public final class ShortcutSettingsDialog extends JDialog {
    private final Map<ShortcutId, KeyStroke> values = new EnumMap<>(ShortcutId.class);
    private final ShortcutTableModel model = new ShortcutTableModel();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField();
    private final JTextField recorder = new JTextField();
    private ShortcutId selected;
    private final Runnable applied;

    public ShortcutSettingsDialog(Window owner) {
        this(owner, () -> { });
    }

    public ShortcutSettingsDialog(Window owner, Runnable applied) {
        super(owner, text("dialog.shortcuts"), Dialog.ModalityType.APPLICATION_MODAL);
        this.applied = applied;
        values.putAll(ShortcutManager.load());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 10));
        ((javax.swing.JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
        search.putClientProperty("JTextField.placeholderText", text("dialog.shortcutSearch"));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { model.refresh(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { model.refresh(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { model.refresh(); }
        });
        add(search, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> selectRow());
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(editorPanel(), BorderLayout.SOUTH);
        installEscapeAction();
        model.refresh();
        setPreferredSize(new Dimension(680, 500));
        pack();
        setLocationRelativeTo(owner);
    }

    private javax.swing.JPanel editorPanel() {
        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(8, 6));
        javax.swing.JPanel row = new javax.swing.JPanel(new BorderLayout(8, 0));
        row.add(new JLabel(text("dialog.shortcutBinding")), BorderLayout.WEST);
        recorder.setEditable(false);
        recorder.setFocusable(true);
        recorder.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (selected == null) return;
                event.consume();
                KeyStroke stroke = KeyStroke.getKeyStrokeForEvent(event);
                values.put(selected, stroke);
                recorder.setText(stroke.toString());
                model.fireTableDataChanged();
            }
        });
        row.add(recorder, BorderLayout.CENTER);
        JButton clear = new JButton(text("dialog.shortcutClear"));
        clear.addActionListener(event -> {
            if (selected != null) {
                values.put(selected, null);
                recorder.setText(text("dialog.shortcutUnassigned"));
                model.fireTableDataChanged();
            }
        });
        row.add(clear, BorderLayout.EAST);
        panel.add(row, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        JButton reset = new JButton(text("dialog.shortcutReset"));
        reset.addActionListener(event -> {
            values.clear();
            values.putAll(ShortcutManager.load());
            model.fireTableDataChanged();
            selectRow();
        });
        JButton cancel = new JButton(text("dialog.cancel"));
        cancel.addActionListener(event -> dispose());
        JButton apply = new JButton(text("dialog.apply"));
        apply.addActionListener(event -> applyChanges());
        actions.add(reset);
        actions.add(cancel);
        actions.add(apply);
        getRootPane().setDefaultButton(apply);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void selectRow() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.visible().size()) {
            selected = null;
            recorder.setText("");
            return;
        }
        selected = model.visible().get(row);
        KeyStroke stroke = values.get(selected);
        recorder.setText(stroke == null ? text("dialog.shortcutUnassigned") : stroke.toString());
    }

    private void applyChanges() {
        for (ShortcutId id : ShortcutId.values()) {
            String conflict = ShortcutManager.conflict(id, values.get(id), values);
            if (!conflict.isBlank()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        text("dialog.shortcutConflict", text(id.labelKey()), text(conflict)),
                        text("dialog.error"), javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        ShortcutManager.save(values);
        applied.run();
        dispose();
    }

    private void installEscapeAction() {
        getRootPane().getInputMap(javax.swing.JRootPane.WHEN_IN_FOCUSED_WINDOW)
                .put(ShortcutManager.stroke(ShortcutId.CLOSE_CHILD), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dispose(); }
        });
    }

    private final class ShortcutTableModel extends AbstractTableModel {
        private List<ShortcutId> visible = List.of();

        void refresh() {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            List<ShortcutId> result = new ArrayList<>();
            for (ShortcutId id : ShortcutId.values()) {
                String label = text(id.labelKey());
                if (query.isEmpty() || label.toLowerCase(Locale.ROOT).contains(query)
                        || id.name().toLowerCase(Locale.ROOT).contains(query)) {
                    result.add(id);
                }
            }
            visible = List.copyOf(result);
            fireTableDataChanged();
        }

        List<ShortcutId> visible() { return visible; }

        @Override public int getRowCount() { return visible.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int column) {
            return switch (column) {
                case 0 -> text("dialog.shortcutCommand");
                case 1 -> text("dialog.shortcutScope");
                default -> text("dialog.shortcutKey");
            };
        }
        @Override public Object getValueAt(int row, int column) {
            ShortcutId id = visible.get(row);
            return switch (column) {
                case 0 -> text(id.labelKey());
                case 1 -> id.scope().name();
                default -> values.get(id) == null ? text("dialog.shortcutUnassigned") : values.get(id).toString();
            };
        }
    }
}
