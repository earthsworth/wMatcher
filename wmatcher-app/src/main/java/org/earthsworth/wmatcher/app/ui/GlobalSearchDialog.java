package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import org.earthsworth.wmatcher.app.ShortcutId;
import org.earthsworth.wmatcher.app.ShortcutManager;
import org.earthsworth.wmatcher.app.WorkspaceController;

public final class GlobalSearchDialog extends JDialog {
    private static final int MAXIMUM_RESULTS = 1_000;
    private final WorkspaceController controller;
    private final Consumer<WorkspaceController.SearchHit> navigator;
    private final Supplier<Boolean> canonical;
    private final JTextField query = new JTextField();
    private final JComboBox<SideChoice> side = new JComboBox<>();
    private final JCheckBox classes = new JCheckBox(text("search.classes"), true);
    private final JCheckBox members = new JCheckBox(text("search.members"), true);
    private final JCheckBox files = new JCheckBox(text("search.files"), true);
    private final JCheckBox textSearch = new JCheckBox(text("search.text"), true);
    private final SearchTableModel model = new SearchTableModel();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final Timer debounce = new Timer(300, event -> startSearch());
    private final AtomicLong generation = new AtomicLong();
    private AtomicBoolean cancellation = new AtomicBoolean();
    private Future<?> job;

    public GlobalSearchDialog(
            Window owner,
            WorkspaceController controller,
            Consumer<WorkspaceController.SearchHit> navigator,
            Supplier<Boolean> canonical) {
        super(owner, text("dialog.globalSearch"), Dialog.ModalityType.MODELESS);
        this.controller = controller;
        this.navigator = navigator;
        this.canonical = canonical;
        debounce.setRepeats(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        add(searchBar(), BorderLayout.NORTH);
        configureTable();
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(120, 16));
        footer.add(status, BorderLayout.CENTER);
        footer.add(progress, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        installActions();
        setPreferredSize(new Dimension(860, 560));
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(query::requestFocusInWindow);
    }

    private JPanel searchBar() {
        JPanel panel = new JPanel(new BorderLayout(8, 5));
        query.putClientProperty("JTextField.placeholderText", text("search.placeholder"));
        query.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { schedule(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { schedule(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { schedule(); }
        });
        side.addItem(new SideChoice(text("search.sideAll"), WorkspaceController.SearchSide.ALL));
        side.addItem(new SideChoice(text("search.sideOld"), WorkspaceController.SearchSide.OLD));
        side.addItem(new SideChoice(text("search.sideNew"), WorkspaceController.SearchSide.NEW));
        side.addActionListener(event -> schedule());
        panel.add(query, BorderLayout.CENTER);
        panel.add(side, BorderLayout.EAST);
        JPanel scopes = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        for (JCheckBox option : List.of(classes, members, files, textSearch)) {
            option.addActionListener(event -> schedule());
            scopes.add(option);
        }
        panel.add(scopes, BorderLayout.SOUTH);
        return panel;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(330);
        table.getColumnModel().getColumn(3).setPreferredWidth(55);
        table.getColumnModel().getColumn(4).setPreferredWidth(330);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) navigateSelected();
            }
        });
    }

    private void installActions() {
        table.getInputMap().put(ShortcutManager.stroke(ShortcutId.ACCEPT), "open-result");
        table.getActionMap().put("open-result", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { navigateSelected(); }
        });
        getRootPane().getInputMap(javax.swing.JRootPane.WHEN_IN_FOCUSED_WINDOW)
                .put(ShortcutManager.stroke(ShortcutId.CLOSE_CHILD), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dispose(); }
        });
    }

    private void schedule() {
        debounce.restart();
    }

    private void startSearch() {
        cancelSearch();
        long currentGeneration = generation.incrementAndGet();
        model.clear();
        String value = query.getText().trim();
        if (value.isEmpty()) {
            status.setText(" ");
            progress.setVisible(false);
            return;
        }
        EnumSet<WorkspaceController.SearchType> types = EnumSet.noneOf(WorkspaceController.SearchType.class);
        if (classes.isSelected()) types.add(WorkspaceController.SearchType.CLASS);
        if (members.isSelected()) types.add(WorkspaceController.SearchType.MEMBER);
        if (files.isSelected()) types.add(WorkspaceController.SearchType.FILE);
        if (textSearch.isSelected()) types.add(WorkspaceController.SearchType.TEXT);
        if (types.isEmpty()) return;
        SideChoice selectedSide = (SideChoice) side.getSelectedItem();
        cancellation = new AtomicBoolean();
        progress.setVisible(true);
        status.setText(text("search.searching"));
        var request = new WorkspaceController.SearchRequest(value,
                selectedSide == null ? WorkspaceController.SearchSide.ALL : selectedSide.side(),
                types, canonical.get(), cancellation::get);
        job = controller.search(request,
                hit -> SwingUtilities.invokeLater(() -> addHit(currentGeneration, hit)),
                update -> SwingUtilities.invokeLater(() -> updateProgress(currentGeneration, update)),
                error -> SwingUtilities.invokeLater(() -> searchFailed(currentGeneration, error)));
    }

    private void addHit(long currentGeneration, WorkspaceController.SearchHit hit) {
        if (generation.get() != currentGeneration || model.getRowCount() >= MAXIMUM_RESULTS) return;
        model.add(hit);
        status.setText(text("search.resultCount", model.getRowCount()));
    }

    private void updateProgress(long currentGeneration, WorkspaceController.SearchProgress update) {
        if (generation.get() != currentGeneration) return;
        if (update.total() > 0 && update.completed() >= update.total()) {
            progress.setVisible(false);
        }
    }

    private void searchFailed(long currentGeneration, Throwable error) {
        if (generation.get() != currentGeneration) return;
        progress.setVisible(false);
        status.setText(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }

    private void navigateSelected() {
        int row = table.getSelectedRow();
        if (row >= 0) navigator.accept(model.hit(row));
    }

    private void cancelSearch() {
        cancellation.set(true);
        if (job != null) job.cancel(true);
        job = null;
    }

    @Override
    public void dispose() {
        debounce.stop();
        cancelSearch();
        super.dispose();
    }

    private record SideChoice(String label, WorkspaceController.SearchSide side) {
        @Override public String toString() { return label; }
    }

    private static final class SearchTableModel extends AbstractTableModel {
        private final List<WorkspaceController.SearchHit> hits = new ArrayList<>();

        void clear() { int size = hits.size(); hits.clear(); if (size > 0) fireTableRowsDeleted(0, size - 1); }
        void add(WorkspaceController.SearchHit hit) {
            int row = hits.size(); hits.add(hit); fireTableRowsInserted(row, row);
        }
        WorkspaceController.SearchHit hit(int row) { return hits.get(row); }
        @Override public int getRowCount() { return hits.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int column) {
            return switch (column) {
                case 0 -> text("search.side");
                case 1 -> text("search.kind");
                case 2 -> text("search.location");
                case 3 -> text("search.line");
                default -> text("search.preview");
            };
        }
        @Override public Object getValueAt(int row, int column) {
            WorkspaceController.SearchHit hit = hits.get(row);
            return switch (column) {
                case 0 -> text(hit.leftSide() ? "search.sideOld" : "search.sideNew");
                case 1 -> text("search.kind." + hit.type().name().toLowerCase(java.util.Locale.ROOT));
                case 2 -> hit.path();
                case 3 -> hit.line() == 0 ? "" : hit.line();
                default -> hit.preview();
            };
        }
    }
}
