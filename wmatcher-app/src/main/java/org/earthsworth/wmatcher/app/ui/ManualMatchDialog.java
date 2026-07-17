package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.earthsworth.wmatcher.core.model.EntityId;

final class ManualMatchDialog extends JDialog {
    private final List<Choice> allChoices;
    private final ChoiceTableModel model = new ChoiceTableModel();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField();
    private final JButton choose = new JButton(text("dialog.choose"));
    private EntityId result;

    private ManualMatchDialog(Window owner, EntityId source, List<Choice> choices) {
        super(owner, text("dialog.manual"), Dialog.ModalityType.APPLICATION_MODAL);
        allChoices = List.copyOf(choices);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 10));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));
        add(header(source), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        configureTable();
        installSearch();
        installKeyboardActions();
        applyFilter();
        setPreferredSize(new Dimension(760, 480));
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(search::requestFocusInWindow);
    }

    static EntityId showDialog(Component parent, EntityId source, List<Choice> choices) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        ManualMatchDialog dialog = new ManualMatchDialog(owner, source, choices);
        dialog.setVisible(true);
        return dialog.result;
    }

    static List<Choice> filterChoices(List<Choice> choices, String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.copyOf(choices);
        }
        return choices.stream().filter(choice -> {
            EntityId target = choice.target();
            return target.externalName().toLowerCase(Locale.ROOT).contains(normalized)
                    || target.name().toLowerCase(Locale.ROOT).contains(normalized)
                    || target.descriptor().toLowerCase(Locale.ROOT).contains(normalized);
        }).toList();
    }

    private JPanel header(EntityId source) {
        JPanel header = new JPanel(new BorderLayout(0, 7));
        header.add(new JLabel(text("dialog.manualSource", source.externalName())), BorderLayout.NORTH);
        search.putClientProperty("JTextField.placeholderText", text("dialog.manualSearch"));
        header.add(search, BorderLayout.CENTER);
        return header;
    }

    private JPanel actions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = new JButton(text("dialog.cancel"));
        cancel.addActionListener(event -> dispose());
        choose.addActionListener(event -> acceptSelection());
        getRootPane().setDefaultButton(choose);
        actions.add(cancel);
        actions.add(choose);
        return actions;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(520);
        table.getColumnModel().getColumn(1).setPreferredWidth(190);
        table.getSelectionModel().addListSelectionListener(event -> updateChooseButton());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    acceptSelection();
                }
            }
        });
    }

    private void installSearch() {
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
    }

    private void installKeyboardActions() {
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "next-result");
        search.getActionMap().put("next-result", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { moveSelection(1); }
        });
        search.getInputMap().put(KeyStroke.getKeyStroke("UP"), "previous-result");
        search.getActionMap().put("previous-result", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { moveSelection(-1); }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "choose");
        table.getActionMap().put("choose", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { acceptSelection(); }
        });
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dispose(); }
        });
    }

    private void moveSelection(int direction) {
        if (model.getRowCount() == 0) {
            return;
        }
        int selected = table.getSelectedRow();
        int next = Math.max(0, Math.min(model.getRowCount() - 1, selected + direction));
        table.setRowSelectionInterval(next, next);
        table.scrollRectToVisible(table.getCellRect(next, 0, true));
    }

    private void applyFilter() {
        List<Choice> filtered = filterChoices(allChoices, search.getText());
        model.setChoices(filtered);
        int preferred = -1;
        for (int index = 0; index < filtered.size(); index++) {
            if (filtered.get(index).current()) {
                preferred = index;
                break;
            }
        }
        if (!filtered.isEmpty()) {
            table.setRowSelectionInterval(preferred >= 0 ? preferred : 0, preferred >= 0 ? preferred : 0);
        }
        updateChooseButton();
    }

    private void updateChooseButton() {
        choose.setEnabled(table.getSelectedRow() >= 0);
    }

    private void acceptSelection() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            result = model.choice(row).target();
            dispose();
        }
    }

    record Choice(EntityId target, EntityId occupant, boolean current) { }

    private static final class ChoiceTableModel extends AbstractTableModel {
        private final String[] columns = {text("candidate.right"), text("candidate.availability")};
        private List<Choice> choices = List.of();

        void setChoices(List<Choice> values) {
            choices = List.copyOf(values);
            fireTableDataChanged();
        }

        Choice choice(int row) {
            return choices.get(row);
        }

        @Override public int getRowCount() { return choices.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Choice choice = choices.get(rowIndex);
            if (columnIndex == 0) {
                return choice.target().externalName();
            }
            if (choice.current()) {
                return text("candidate.availability.current");
            }
            if (choice.occupant() == null) {
                return text("candidate.availability.free");
            }
            return text("candidate.availability.occupied", choice.occupant().externalName());
        }
    }
}
