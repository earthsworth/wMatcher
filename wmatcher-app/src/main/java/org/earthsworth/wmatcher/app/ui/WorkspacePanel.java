package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.earthsworth.wmatcher.app.WorkspaceController;
import org.earthsworth.wmatcher.core.model.ChangeKind;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.core.model.ScoreBreakdown;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

public final class WorkspacePanel extends JPanel {
    private final WorkspaceController controller;
    private final Consumer<Throwable> errorHandler;
    private WorkspaceController.Workspace workspace;
    private final JLabel summary = new JLabel();
    private final JTextField search = new JTextField();
    private final JComboBox<FilterOption> filter = new JComboBox<>();
    private final JTree tree = new JTree();
    private final JScrollPane treeScrollPane = new JScrollPane(tree);
    private final JTextArea overview = new JTextArea();
    private final CandidateTableModel candidateModel = new CandidateTableModel();
    private final JTable candidates = new JTable(candidateModel);
    private final JButton lockCandidate = new JButton(text("candidate.lock"));
    private final JButton unlockCandidate = new JButton(text("candidate.unlock"));
    private final JButton manualCandidate = new JButton(text("candidate.manual"));
    private final SideBySideTextPanel structure = new SideBySideTextPanel();
    private final SideBySideTextPanel bytecode = new SideBySideTextPanel();
    private final SideBySideTextPanel source = new SideBySideTextPanel();
    private final JCheckBox semantic = new JCheckBox(text("detail.semantic"), true);
    private final JCheckBox canonical = new JCheckBox(text("detail.canonical"), true);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JSplitPane workspaceSplit = new JSplitPane();
    private final Set<String> expandedTreeKeys = new LinkedHashSet<>();
    private DiffNode selected;
    private long generation;
    private long sourceGeneration = -1;
    private int restoredScrollPosition;
    private boolean restoringTreeState;

    public WorkspacePanel(
            WorkspaceController controller,
            WorkspaceController.Workspace workspace,
            Consumer<Throwable> errorHandler) {
        super(new BorderLayout());
        this.controller = controller;
        this.workspace = workspace;
        this.errorHandler = errorHandler;
        buildUi();
        applyUiState(workspace.uiState());
        refresh(workspace);
    }

    public void refresh(WorkspaceController.Workspace updated) {
        workspace = updated;
        long changed = updated.differences().nodes().stream().filter(DiffNode::changed).count();
        summary.setText(text("workspace.summary",
                updated.left().classes().size(),
                updated.right().classes().size(),
                updated.matches().confirmed().size(),
                updated.matches().candidates().size(),
                changed));
        String selectedKey = selected == null ? "" : stableNodeKey(selected);
        rebuildTree(selectedKey);
    }

    public ProjectUiState uiState() {
        FilterOption selectedFilter = (FilterOption) filter.getSelectedItem();
        return new ProjectUiState(search.getText(),
                selectedFilter == null ? Set.of() : Set.of(selectedFilter.kind().name()),
                selected == null ? "" : stableNodeKey(selected),
                expandedTreeKeys,
                treeScrollPane.getVerticalScrollBar().getValue(),
                workspaceSplit.getDividerLocation());
    }

    public void undo() {
        controller.undoMappings(this::mappingChanged, errorHandler);
    }

    public void redo() {
        controller.redoMappings(this::mappingChanged, errorHandler);
    }

    public void setMinimapsVisible(boolean visible) {
        structure.setMinimapVisible(visible);
        bytecode.setMinimapVisible(visible);
        source.setMinimapVisible(visible);
    }

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        top.add(summary, BorderLayout.WEST);
        add(top, BorderLayout.NORTH);

        JPanel navigation = new JPanel(new BorderLayout());
        JPanel filters = new JPanel(new BorderLayout(6, 0));
        filters.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
        search.putClientProperty("JTextField.placeholderText", text("workspace.search"));
        filter.addItem(new FilterOption(text("workspace.filter.all"), FilterKind.ALL));
        filter.addItem(new FilterOption(text("workspace.filter.changed"), FilterKind.CHANGED));
        filter.addItem(new FilterOption(text("workspace.filter.added"), FilterKind.ADDED));
        filter.addItem(new FilterOption(text("workspace.filter.removed"), FilterKind.REMOVED));
        filter.addItem(new FilterOption(text("workspace.filter.modified"), FilterKind.MODIFIED));
        filter.addItem(new FilterOption(text("workspace.filter.unresolved"), FilterKind.UNRESOLVED));
        filters.add(search, BorderLayout.CENTER);
        filters.add(filter, BorderLayout.EAST);
        navigation.add(filters, BorderLayout.NORTH);
        navigation.add(treeScrollPane, BorderLayout.CENTER);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DiffTreeRenderer());
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                if (!restoringTreeState) {
                    treeEntry(event.getPath()).ifPresent(entry -> expandedTreeKeys.add(entry.stableKey()));
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                if (!restoringTreeState) {
                    treeEntry(event.getPath()).ifPresent(entry -> expandedTreeKeys.remove(entry.stableKey()));
                }
            }
        });
        tree.addTreeSelectionListener(event -> {
            Object value = tree.getLastSelectedPathComponent();
            if (value instanceof DefaultMutableTreeNode treeNode && treeNode.getUserObject() instanceof TreeEntry entry
                    && entry.node() != null) {
                selectNode(entry.node());
            }
        });

        overview.setEditable(false);
        overview.setLineWrap(true);
        overview.setWrapStyleWord(true);
        overview.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        tabs.addTab(text("tab.overview"), new JScrollPane(overview));
        tabs.addTab(text("tab.candidates"), candidatePanel());
        tabs.addTab(text("tab.structure"), structure);

        JPanel bytecodePanel = new JPanel(new BorderLayout());
        JPanel bytecodeOptions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bytecodeOptions.add(semantic);
        bytecodePanel.add(bytecodeOptions, BorderLayout.NORTH);
        bytecodePanel.add(bytecode, BorderLayout.CENTER);
        tabs.addTab(text("tab.bytecode"), bytecodePanel);

        JPanel sourcePanel = new JPanel(new BorderLayout());
        JPanel sourceOptions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sourceOptions.add(canonical);
        sourcePanel.add(sourceOptions, BorderLayout.NORTH);
        sourcePanel.add(source, BorderLayout.CENTER);
        tabs.addTab(text("tab.source"), sourcePanel);

        semantic.addActionListener(event -> loadBytecode());
        canonical.addActionListener(event -> {
            sourceGeneration = -1;
            loadSource();
        });
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedIndex() == 4) {
                loadSource();
            }
        });

        structure.setSyntaxStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        source.setSyntaxStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        workspaceSplit.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
        workspaceSplit.setLeftComponent(navigation);
        workspaceSplit.setRightComponent(tabs);
        workspaceSplit.setResizeWeight(0.28);
        workspaceSplit.setOneTouchExpandable(true);
        workspaceSplit.setDividerLocation(340);
        add(workspaceSplit, BorderLayout.CENTER);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { rebuildTree(""); }
            @Override public void removeUpdate(DocumentEvent event) { rebuildTree(""); }
            @Override public void changedUpdate(DocumentEvent event) { rebuildTree(""); }
        });
        filter.addActionListener(event -> rebuildTree(""));
    }

    private void applyUiState(ProjectUiState state) {
        expandedTreeKeys.clear();
        expandedTreeKeys.addAll(state.expandedTreeKeys());
        restoredScrollPosition = state.treeScrollPosition();
        workspaceSplit.setDividerLocation(state.navigationDividerLocation());
        search.setText(state.search());
        if (!state.filters().isEmpty()) {
            String stored = state.filters().iterator().next();
            for (int index = 0; index < filter.getItemCount(); index++) {
                if (filter.getItemAt(index).kind().name().equals(stored)) {
                    filter.setSelectedIndex(index);
                    break;
                }
            }
        }
        if (!state.selectedKey().isBlank()) {
            rebuildTree(state.selectedKey());
        }
    }

    private JPanel candidatePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        candidates.setAutoCreateRowSorter(true);
        candidates.setFillsViewportHeight(true);
        candidates.getSelectionModel().addListSelectionListener(event -> updateCandidateActions());
        panel.add(new JScrollPane(candidates), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lockCandidate.addActionListener(event -> lockSelectedCandidate());
        unlockCandidate.addActionListener(event -> unlockSelected());
        manualCandidate.addActionListener(event -> chooseManual());
        actions.add(lockCandidate);
        actions.add(unlockCandidate);
        actions.add(manualCandidate);
        panel.add(actions, BorderLayout.SOUTH);
        updateCandidateActions();
        return panel;
    }

    private void rebuildTree(String selectedKey) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        String effectiveSelection = selectedKey.isBlank() && selected != null ? stableNodeKey(selected) : selectedKey;
        int effectiveScroll = treeScrollPane.getVerticalScrollBar().getValue();
        if (effectiveScroll == 0 && restoredScrollPosition > 0) {
            effectiveScroll = restoredScrollPosition;
            restoredScrollPosition = 0;
        }
        DefaultMutableTreeNode classRoot = new DefaultMutableTreeNode(
                new TreeEntry("root:classes", text("tree.classes"), null));
        DefaultMutableTreeNode resourceRoot = new DefaultMutableTreeNode(
                new TreeEntry("root:resources", text("tree.resources"), null));
        root.add(classRoot);
        root.add(resourceRoot);
        Map<String, DefaultMutableTreeNode> packages = new HashMap<>();
        Map<String, DefaultMutableTreeNode> classNodes = new HashMap<>();
        List<DiffNode> visible = workspace.differences().nodes().stream()
                .filter(this::visible)
                .toList();
        visible.stream().filter(node -> node.kind() == EntityKind.CLASS).forEach(node -> {
            String className = entityName(node);
            int slash = className.lastIndexOf('/');
            String packageName = slash < 0 ? "" : className.substring(0, slash);
            String simpleName = slash < 0 ? className : className.substring(slash + 1);
            DefaultMutableTreeNode parent = packageNode(classRoot, packages, "classes", packageName);
            DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(
                    new TreeEntry(stableNodeKey(node), label(simpleName, node), node));
            parent.add(classNode);
            if (node.left() != null) {
                classNodes.put(node.left().name(), classNode);
            }
            if (node.right() != null) {
                classNodes.put(node.right().name(), classNode);
            }
        });
        visible.stream().filter(node -> node.kind() == EntityKind.FIELD || node.kind() == EntityKind.METHOD)
                .forEach(node -> {
                    EntityId id = node.left() != null ? node.left() : node.right();
                    DefaultMutableTreeNode parent = classNodes.get(id.owner());
                    if (parent != null) {
                        parent.add(new DefaultMutableTreeNode(new TreeEntry(
                                stableNodeKey(node), label(id.name() + id.descriptor(), node), node)));
                    }
                });
        Map<String, DefaultMutableTreeNode> resourceDirectories = new HashMap<>();
        visible.stream().filter(node -> node.kind() == EntityKind.RESOURCE).forEach(node -> {
            String name = entityName(node);
            int slash = name.lastIndexOf('/');
            String directory = slash < 0 ? "" : name.substring(0, slash);
            String simple = slash < 0 ? name : name.substring(slash + 1);
            DefaultMutableTreeNode parent = packageNode(resourceRoot, resourceDirectories, "resources", directory);
            parent.add(new DefaultMutableTreeNode(
                    new TreeEntry(stableNodeKey(node), label(simple, node), node)));
        });
        restoringTreeState = true;
        try {
            tree.setModel(new DefaultTreeModel(root));
            restoreExpandedPaths(root);
            if (!effectiveSelection.isBlank()) {
                TreePath selectedPath = findTreePath(root, effectiveSelection);
                if (selectedPath != null) {
                    tree.setSelectionPath(selectedPath);
                    tree.scrollPathToVisible(selectedPath);
                }
            }
        } finally {
            restoringTreeState = false;
        }
        int scrollToRestore = effectiveScroll;
        SwingUtilities.invokeLater(() -> treeScrollPane.getVerticalScrollBar().setValue(scrollToRestore));
    }

    private boolean visible(DiffNode node) {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty() && !node.displayName().toLowerCase(Locale.ROOT).contains(query)) {
            return false;
        }
        FilterOption option = (FilterOption) filter.getSelectedItem();
        FilterKind kind = option == null ? FilterKind.ALL : option.kind();
        return switch (kind) {
            case ALL -> true;
            case CHANGED -> node.changed();
            case ADDED -> node.changes().contains(ChangeKind.ADDED);
            case REMOVED -> node.changes().contains(ChangeKind.REMOVED);
            case MODIFIED -> node.changes().contains(ChangeKind.STRUCTURE)
                    || node.changes().contains(ChangeKind.CODE) || node.changes().contains(ChangeKind.RESOURCE)
                    || node.changes().contains(ChangeKind.RENAMED) || node.changes().contains(ChangeKind.MOVED);
            case UNRESOLVED -> node.changes().contains(ChangeKind.UNRESOLVED);
        };
    }

    private void selectNode(DiffNode node) {
        selected = node;
        generation++;
        sourceGeneration = -1;
        overview.setText(overview(node));
        updateCandidates(node);
        if (node.kind() == EntityKind.CLASS || node.kind() == EntityKind.FIELD || node.kind() == EntityKind.METHOD) {
            EntityId leftId = node.left();
            EntityId rightId = node.right();
            String leftClass = leftId == null ? null : leftId.kind() == EntityKind.CLASS ? leftId.name() : leftId.owner();
            String rightClass = rightId == null ? null : rightId.kind() == EntityKind.CLASS ? rightId.name() : rightId.owner();
            structure.setTexts(formatClass(workspace.left().classes().get(leftClass)),
                    formatClass(workspace.right().classes().get(rightClass)));
            loadBytecode();
            source.setTexts(text("detail.select"), text("detail.select"));
            if (tabs.getSelectedIndex() == 4) {
                loadSource();
            }
        } else if (node.kind() == EntityKind.RESOURCE) {
            structure.setLoading(text("detail.loading"));
            loadPair(
                    callback -> controller.loadResource(node, true, callback, errorHandler),
                    callback -> controller.loadResource(node, false, callback, errorHandler),
                    structure,
                    generation);
            bytecode.setTexts("", "");
            source.setTexts("", "");
        }
    }

    private void loadBytecode() {
        DiffNode node = selected;
        if (node == null || node.kind() != EntityKind.CLASS) {
            bytecode.setTexts("", "");
            return;
        }
        long requestGeneration = generation;
        bytecode.setLoading(text("detail.loading"));
        loadPair(
                callback -> controller.loadBytecode(node, true, semantic.isSelected(), callback, errorHandler),
                callback -> controller.loadBytecode(node, false, semantic.isSelected(), callback, errorHandler),
                bytecode,
                requestGeneration);
    }

    private void loadSource() {
        DiffNode node = selected;
        if (node == null || node.kind() != EntityKind.CLASS || sourceGeneration == generation) {
            return;
        }
        sourceGeneration = generation;
        long requestGeneration = generation;
        source.setLoading(text("detail.loading"));
        loadPair(
                callback -> controller.loadSource(node, true, canonical.isSelected(), callback, errorHandler),
                callback -> controller.loadSource(node, false, canonical.isSelected(), callback, errorHandler),
                source,
                requestGeneration);
    }

    private void loadPair(
            Consumer<Consumer<String>> leftLoader,
            Consumer<Consumer<String>> rightLoader,
            SideBySideTextPanel target,
            long requestGeneration) {
        AtomicReference<String> leftText = new AtomicReference<>("");
        AtomicReference<String> rightText = new AtomicReference<>("");
        AtomicInteger completed = new AtomicInteger();
        Runnable render = () -> {
            if (completed.incrementAndGet() == 2 && generation == requestGeneration) {
                target.setTexts(leftText.get(), rightText.get());
            }
        };
        leftLoader.accept(value -> {
            leftText.set(value);
            render.run();
        });
        rightLoader.accept(value -> {
            rightText.set(value);
            render.run();
        });
    }

    private void updateCandidates(DiffNode node) {
        if (node.left() == null) {
            candidateModel.setRows(List.of());
            updateCandidateActions();
            return;
        }
        EntityId left = node.left();
        MatchDecision current = currentMatch(left);
        List<CandidateRow> rows = new ArrayList<>();
        if (current != null) {
            rows.add(new CandidateRow(current, true, false));
        }
        List<MatchDecision> ranked = workspace.matches().rankedCandidates()
                .getOrDefault(left, workspace.matches().candidates().getOrDefault(left, List.of()));
        for (MatchDecision candidate : ranked) {
            if (current == null || !candidate.right().equals(current.right())) {
                rows.add(new CandidateRow(candidate, false, current != null));
            }
        }
        candidateModel.setRows(rows);
        if (!rows.isEmpty()) {
            candidates.setRowSelectionInterval(0, 0);
        }
        updateCandidateActions();
    }

    private void lockSelectedCandidate() {
        int viewRow = candidates.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        CandidateRow row = candidateModel.row(candidates.convertRowIndexToModel(viewRow));
        confirmAndLock(row.decision());
    }

    private void unlockSelected() {
        if (selected != null && selected.left() != null) {
            controller.unlockMapping(selected.left(), this::mappingChanged, errorHandler);
        }
    }

    private void chooseManual() {
        if (selected == null || selected.left() == null) {
            return;
        }
        EntityId left = selected.left();
        List<ManualMatchDialog.Choice> choices = manualChoices(left);
        if (choices.isEmpty()) {
            JOptionPane.showMessageDialog(this, text("dialog.noManualTargets"), text("candidate.manual"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        EntityId chosen = ManualMatchDialog.showDialog(this, left, choices);
        if (chosen != null) {
            ScoreBreakdown score = workspace.matches().rankedCandidates().getOrDefault(left, List.of()).stream()
                    .filter(candidate -> candidate.right().equals(chosen))
                    .map(MatchDecision::score)
                    .findFirst()
                    .orElseGet(() -> new ScoreBreakdown(0.0, Map.of("manual", 0.0)));
            confirmAndLock(new MatchDecision(left, chosen, MatchStatus.SUGGESTED, score));
        }
    }

    private List<ManualMatchDialog.Choice> manualChoices(EntityId left) {
        Map<EntityId, EntityId> occupants = new HashMap<>();
        workspace.matches().confirmed().forEach(match -> occupants.put(match.right(), match.left()));
        Set<EntityId> targets = new LinkedHashSet<>(workspace.matches().unmatchedRight());
        targets.addAll(occupants.keySet());
        MatchDecision current = currentMatch(left);
        EntityId currentRight = current == null ? null : current.right();
        return targets.stream()
                .filter(right -> manuallyCompatible(left, right))
                .sorted(Comparator.comparing(EntityId::externalName))
                .map(right -> new ManualMatchDialog.Choice(
                        right, occupants.get(right), right.equals(currentRight)))
                .toList();
    }

    private boolean manuallyCompatible(EntityId left, EntityId right) {
        if (right.kind() != left.kind() || !ownersCompatible(left, right)) {
            return false;
        }
        if (left.kind() != EntityKind.METHOD) {
            return true;
        }
        boolean leftSpecial = left.name().startsWith("<");
        boolean rightSpecial = right.name().startsWith("<");
        return leftSpecial == rightSpecial && (!leftSpecial || left.name().equals(right.name()));
    }

    private void confirmAndLock(MatchDecision decision) {
        EntityId occupant = workspace.matches().confirmed().stream()
                .filter(match -> match.right().equals(decision.right()))
                .map(MatchDecision::left)
                .filter(left -> !left.equals(decision.left()))
                .findFirst()
                .orElse(null);
        if (!replacementAccepted(decision.left(), occupant, () -> JOptionPane.showConfirmDialog(this,
                text("dialog.replaceMapping", decision.right().externalName(), occupant.externalName()),
                text("dialog.replaceMappingTitle"), JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)) {
            return;
        }
        controller.lockMapping(decision, this::mappingChanged, errorHandler);
    }

    static boolean replacementAccepted(
            EntityId source,
            EntityId occupant,
            java.util.function.BooleanSupplier confirmation) {
        return occupant == null || occupant.equals(source) || confirmation.getAsBoolean();
    }

    private MatchDecision currentMatch(EntityId left) {
        return workspace.matches().confirmed().stream()
                .filter(match -> match.left().equals(left))
                .findFirst()
                .orElse(null);
    }

    private void updateCandidateActions() {
        int viewRow = candidates.getSelectedRow();
        CandidateRow row = viewRow < 0 ? null
                : candidateModel.row(candidates.convertRowIndexToModel(viewRow));
        boolean alreadyLocked = row != null && row.current()
                && row.decision().status() == MatchStatus.MANUAL_LOCKED;
        lockCandidate.setEnabled(row != null && !alreadyLocked);
        unlockCandidate.setEnabled(selected != null && selected.left() != null
                && workspace.lockedMappings().containsKey(selected.left()));
        manualCandidate.setEnabled(selected != null && selected.left() != null
                && selected.left().kind() != EntityKind.RESOURCE);
    }

    private boolean ownersCompatible(EntityId left, EntityId right) {
        if (left.kind() == EntityKind.CLASS) {
            return true;
        }
        EntityId mappedOwner = workspace.matches().confirmedMappings().get(EntityId.classId(left.owner()));
        return EntityId.classId(right.owner()).equals(mappedOwner);
    }

    private void mappingChanged(WorkspaceController.Workspace updated) {
        refresh(updated);
    }

    private String overview(DiffNode node) {
        StringBuilder result = new StringBuilder();
        result.append(node.displayName()).append("\n\n");
        result.append("Kind: ").append(node.kind()).append('\n');
        result.append("Changes: ").append(node.changes().isEmpty() ? "UNCHANGED" : node.changes()).append('\n');
        result.append("Old: ").append(node.left() == null ? "—" : node.left().externalName()).append('\n');
        result.append("New: ").append(node.right() == null ? "—" : node.right().externalName()).append('\n');
        if (node.left() != null) {
            workspace.matches().confirmed().stream().filter(match -> match.left().equals(node.left())).findFirst()
                    .ifPresent(match -> {
                        result.append("\nMatch: ").append(match.status()).append('\n');
                        result.append("Confidence: ").append(String.format(Locale.ROOT, "%.1f%%",
                                match.score().total() * 100)).append('\n');
                        match.score().components().forEach((name, value) -> result.append("  ").append(name)
                                .append(": ").append(String.format(Locale.ROOT, "%.2f", value)).append('\n'));
                    });
        }
        return result.toString();
    }

    private static String formatClass(ClassModel model) {
        if (model == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        result.append("class ").append(model.internalName()).append('\n');
        result.append("version ").append(model.classFileVersion()).append("  access 0x")
                .append(Integer.toHexString(model.access())).append('\n');
        result.append("extends ").append(model.superName()).append('\n');
        if (!model.interfaces().isEmpty()) {
            result.append("implements ").append(String.join(", ", model.interfaces())).append('\n');
        }
        if (!model.annotations().isEmpty()) {
            result.append("annotations ").append(model.annotations()).append('\n');
        }
        result.append("\nFields\n");
        for (FieldModel field : model.fields()) {
            result.append("  ").append(field.name()).append(' ').append(field.descriptor()).append('\n');
        }
        result.append("\nMethods\n");
        for (MethodModel method : model.methods()) {
            result.append("  ").append(method.name()).append(method.descriptor())
                    .append("  [").append(method.instructionCount()).append(" instructions]\n");
        }
        return result.toString();
    }

    private static String entityName(DiffNode node) {
        EntityId id = node.left() != null ? node.left() : node.right();
        return id.kind() == EntityKind.CLASS || id.kind() == EntityKind.RESOURCE ? id.name() : id.owner();
    }

    private static String label(String value, DiffNode node) {
        String marker;
        if (node.changes().contains(ChangeKind.ADDED)) {
            marker = "+ ";
        } else if (node.changes().contains(ChangeKind.REMOVED)) {
            marker = "− ";
        } else if (node.changes().contains(ChangeKind.UNRESOLVED)) {
            marker = "? ";
        } else if (node.changed()) {
            marker = "~ ";
        } else {
            marker = "  ";
        }
        return marker + value;
    }

    private static DefaultMutableTreeNode packageNode(
            DefaultMutableTreeNode root,
            Map<String, DefaultMutableTreeNode> cache,
            String namespace,
            String path) {
        if (path.isBlank()) {
            return root;
        }
        DefaultMutableTreeNode parent = root;
        StringBuilder current = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(segment);
            String key = current.toString();
            DefaultMutableTreeNode existing = cache.get(key);
            if (existing == null) {
                existing = new DefaultMutableTreeNode(
                        new TreeEntry("package:" + namespace + ':' + key, segment, null));
                parent.add(existing);
                cache.put(key, existing);
            }
            parent = existing;
        }
        return parent;
    }

    private static TreePath findTreePath(DefaultMutableTreeNode node, String key) {
        Object user = node.getUserObject();
        if (user instanceof TreeEntry entry && (entry.stableKey().equals(key)
                || entry.node() != null && entry.node().key().equals(key))) {
            return new TreePath(node.getPath());
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(index);
            TreePath found = findTreePath(child, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void restoreExpandedPaths(DefaultMutableTreeNode node) {
        Object user = node.getUserObject();
        if (user instanceof TreeEntry entry && expandedTreeKeys.contains(entry.stableKey())) {
            tree.expandPath(new TreePath(node.getPath()));
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            restoreExpandedPaths((DefaultMutableTreeNode) children.nextElement());
        }
    }

    private static java.util.Optional<TreeEntry> treeEntry(TreePath path) {
        Object value = path.getLastPathComponent();
        if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof TreeEntry entry) {
            return java.util.Optional.of(entry);
        }
        return java.util.Optional.empty();
    }

    private static String stableNodeKey(DiffNode node) {
        EntityId id = node.left() != null ? node.left() : node.right();
        String side = node.left() != null ? "L:" : "R:";
        return "entity:" + side + id.kind() + ':' + id.externalName();
    }

    JTree treeForTesting() {
        return tree;
    }

    JTextField searchForTesting() {
        return search;
    }

    JSplitPane splitForTesting() {
        return workspaceSplit;
    }

    JTable candidatesForTesting() {
        return candidates;
    }

    boolean minimapsVisibleForTesting() {
        return structure.leftMinimapForTesting().isVisible()
                && bytecode.leftMinimapForTesting().isVisible()
                && source.leftMinimapForTesting().isVisible();
    }

    void selectForTesting(DiffNode node) {
        selected = node;
        updateCandidates(node);
    }

    private enum FilterKind { ALL, CHANGED, ADDED, REMOVED, MODIFIED, UNRESOLVED }

    private record FilterOption(String label, FilterKind kind) {
        @Override public String toString() { return label; }
    }

    private record TreeEntry(String stableKey, String label, DiffNode node) {
        @Override public String toString() { return label; }
    }

    private static final class DiffTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean focused) {
            Component component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, focused);
            if (!selected && value instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof TreeEntry entry && entry.node() != null) {
                if (entry.node().changes().contains(ChangeKind.ADDED)) {
                    setForeground(new Color(45, 150, 80));
                } else if (entry.node().changes().contains(ChangeKind.REMOVED)) {
                    setForeground(new Color(205, 70, 70));
                } else if (entry.node().changes().contains(ChangeKind.UNRESOLVED)) {
                    setForeground(new Color(210, 145, 30));
                } else if (entry.node().changed()) {
                    setForeground(UIManager.getColor("Component.accentColor"));
                }
            }
            return component;
        }
    }

    private record CandidateRow(MatchDecision decision, boolean current, boolean alternative) { }

    private static final class CandidateTableModel extends AbstractTableModel {
        private final String[] columns = {
            text("candidate.status"), text("candidate.left"), text("candidate.right"),
            text("candidate.score"), text("candidate.reasons")
        };
        private List<CandidateRow> rows = List.of();

        void setRows(List<CandidateRow> values) {
            rows = List.copyOf(values);
            fireTableDataChanged();
        }

        CandidateRow row(int index) {
            return rows.get(index);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CandidateRow row = rows.get(rowIndex);
            MatchDecision match = row.decision();
            return switch (columnIndex) {
                case 0 -> row.current()
                        ? text("candidate.status.current", statusText(match.status()))
                        : row.alternative() ? text("candidate.status.alternative")
                        : statusText(match.status());
                case 1 -> match.left().externalName();
                case 2 -> match.right().externalName();
                case 3 -> String.format(Locale.ROOT, "%.1f%%", match.score().total() * 100);
                case 4 -> match.score().components().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + '=' + String.format(Locale.ROOT, "%.2f", entry.getValue()))
                        .collect(java.util.stream.Collectors.joining(", "));
                default -> "";
            };
        }

        private static String statusText(MatchStatus status) {
            return switch (status) {
                case EXACT -> text("candidate.status.exact");
                case AUTO_CONFIRMED -> text("candidate.status.auto");
                case MANUAL_LOCKED -> text("candidate.status.locked");
                case SUGGESTED -> text("candidate.status.suggested");
            };
        }
    }
}
