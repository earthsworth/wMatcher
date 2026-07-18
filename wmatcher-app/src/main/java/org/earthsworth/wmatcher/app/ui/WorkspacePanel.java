package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
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
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.Icon;
import javax.swing.ToolTipManager;
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
import org.earthsworth.wmatcher.app.SearchMatcher;
import org.earthsworth.wmatcher.app.WorkspaceController;
import org.earthsworth.wmatcher.app.WorkspaceController.CanonicalNamesDirection;
import org.earthsworth.wmatcher.core.model.ChangeKind;
import org.earthsworth.wmatcher.core.model.ClassClassification;
import org.earthsworth.wmatcher.core.model.ClassPair;
import org.earthsworth.wmatcher.core.model.ClassModel;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.FieldModel;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.MethodModel;
import org.earthsworth.wmatcher.core.model.ResolutionStatus;
import org.earthsworth.wmatcher.core.model.ScoreBreakdown;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkspacePanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspacePanel.class);
    private static final int STRUCTURE_TAB = 2;
    private static final int BYTECODE_TAB = 3;
    private static final int SOURCE_TAB = 4;
    private final WorkspaceController controller;
    private final Consumer<Throwable> errorHandler;
    private WorkspaceController.Workspace workspace;
    private final WorkspaceSummary summary = new WorkspaceSummary();
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
    private final JButton resolutionAction = new JButton();
    private final JButton restorePending = new JButton(text("candidate.restorePending"));
    private final JProgressBar operationProgress = new JProgressBar();
    private final SideBySideTextPanel structure = new SideBySideTextPanel();
    private final SideBySideTextPanel bytecode = new SideBySideTextPanel();
    private final SideBySideTextPanel source = new SideBySideTextPanel();
    private final JCheckBox semantic = new JCheckBox(text("detail.semantic"), true);
    private final JComboBox<CanonicalNameOption> canonicalNames = new JComboBox<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JSplitPane workspaceSplit = new JSplitPane();
    private final Set<String> expandedTreeKeys = new LinkedHashSet<>();
    private DiffNode selected;
    private long generation;
    private long sourceGeneration = -1;
    private int restoredScrollPosition;
    private boolean restoringTreeState;
    private boolean mappingBusy;
    private List<TreeItem> allItems = List.of();
    private final Map<String, DefaultMutableTreeNode> treeNodeIndex = new HashMap<>();
    private Predicate<String> treeSearchPredicate = ignored -> true;

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
        WorkspaceController.Workspace previous = workspace;
        workspace = updated;
        boolean classificationOnly = previous != null && previous.matches() == updated.matches()
                && previous.differences() == updated.differences() && !allItems.isEmpty();
        allItems = classificationOnly ? reclassify(allItems) : allTreeItems();
        updateSummary();
        String selectedKey = selected == null ? "" : stableNodeKey(selected);
        rebuildTree(selectedKey);
    }

    private List<TreeItem> reclassify(List<TreeItem> items) {
        return items.stream().map(item -> {
            DiffNode node = item.node();
            if (node.kind() != EntityKind.CLASS || node.left() == null || node.right() == null) return item;
            ClassClassification classification = workspace.overrides().classifications().getOrDefault(
                    new ClassPair(node.left(), node.right()), ClassClassification.AUTO);
            TreeStatus status = switch (classification) {
                case FORCE_CHANGED -> TreeStatus.CHANGED;
                case FORCE_UNCHANGED -> TreeStatus.UNCHANGED;
                case AUTO -> item.automaticStatus();
            };
            return new TreeItem(node, item.members(), item.automaticStatus(), status, classification);
        }).toList();
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

    public void refreshShortcuts() {
        structure.refreshShortcuts();
        bytecode.refreshShortcuts();
        source.refreshShortcuts();
    }

    public void focusTree() {
        tree.requestFocusInWindow();
    }

    public CanonicalNamesDirection canonicalNamesDirection() {
        CanonicalNameOption selected = (CanonicalNameOption) canonicalNames.getSelectedItem();
        return selected == null ? CanonicalNamesDirection.DISABLED : selected.direction();
    }

    public void selectDetailTab(int index) {
        if (index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
            tabs.requestFocusInWindow();
        }
    }

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        top.add(summary, BorderLayout.WEST);
        operationProgress.setIndeterminate(true);
        operationProgress.setPreferredSize(new java.awt.Dimension(110, 18));
        operationProgress.setVisible(false);
        top.add(operationProgress, BorderLayout.EAST);
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
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                materializeMembers(event.getPath());
                if (!restoringTreeState) {
                    treeEntry(event.getPath()).ifPresent(entry -> expandedTreeKeys.add(
                            expansionStateKey(entry.stableKey())));
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                if (!restoringTreeState) {
                    treeEntry(event.getPath()).ifPresent(entry -> expandedTreeKeys.remove(
                            expansionStateKey(entry.stableKey())));
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
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showTreeMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showTreeMenu(event);
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
        canonicalNames.addItem(new CanonicalNameOption(
                text("detail.canonical.leftToRight"), CanonicalNamesDirection.LEFT_TO_RIGHT));
        canonicalNames.addItem(new CanonicalNameOption(
                text("detail.canonical.rightToLeft"), CanonicalNamesDirection.RIGHT_TO_LEFT));
        canonicalNames.addItem(new CanonicalNameOption(
                text("detail.canonical.disabled"), CanonicalNamesDirection.DISABLED));
        canonicalNames.setSelectedIndex(2);
        sourceOptions.add(new JLabel(text("detail.canonical")));
        sourceOptions.add(canonicalNames);
        sourcePanel.add(sourceOptions, BorderLayout.NORTH);
        sourcePanel.add(source, BorderLayout.CENTER);
        tabs.addTab(text("tab.source"), sourcePanel);

        semantic.addActionListener(event -> loadBytecode());
        canonicalNames.addActionListener(event -> {
            sourceGeneration = -1;
            loadSource();
        });
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedIndex() == BYTECODE_TAB) {
                loadBytecode();
            } else if (tabs.getSelectedIndex() == SOURCE_TAB) {
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

    private void materializeMembers(TreePath path) {
        Object value = path.getLastPathComponent();
        if (!(value instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof TreeEntry entry)
                || entry.members().isEmpty()
                || node.getChildCount() != 1) {
            return;
        }
        Object child = ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject();
        if (!(child instanceof TreeEntry placeholder) || !placeholder.stableKey().startsWith("placeholder:")) {
            return;
        }
        node.removeAllChildren();
        String query = search.getText().trim();
        entry.members().stream()
                .filter(member -> query.isEmpty() || memberMatches(member, treeSearchPredicate))
                .forEach(member -> node.add(memberNode(member)));
        ((DefaultTreeModel) tree.getModel()).reload(node);
        SwingUtilities.invokeLater(() -> tree.expandPath(path));
    }

    private void applyUiState(ProjectUiState state) {
        expandedTreeKeys.clear();
        expandedTreeKeys.addAll(migrateExpandedTreeKeys(state.expandedTreeKeys()));
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
        resolutionAction.addActionListener(event -> toggleSingleSidedResolution());
        restorePending.addActionListener(event -> restoreCurrentToPending());
        actions.add(lockCandidate);
        actions.add(unlockCandidate);
        actions.add(manualCandidate);
        actions.add(resolutionAction);
        actions.add(restorePending);
        panel.add(actions, BorderLayout.SOUTH);
        updateCandidateActions();
        return panel;
    }

    private void rebuildTree(String selectedKey) {
        treeSearchPredicate = prepareSearchPredicate();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        String effectiveSelection = selectedKey.isBlank() && selected != null ? stableNodeKey(selected) : selectedKey;
        int effectiveScroll = treeScrollPane.getVerticalScrollBar().getValue();
        if (effectiveScroll == 0 && restoredScrollPosition > 0) {
            effectiveScroll = restoredScrollPosition;
            restoredScrollPosition = 0;
        }
        List<TreeItem> visible = treeItems();
        Map<TreeStatus, List<TreeItem>> statusGroups = new EnumMap<>(TreeStatus.class);
        for (TreeStatus status : TreeStatus.values()) {
            statusGroups.put(status, new ArrayList<>());
        }
        visible.forEach(item -> statusGroups.get(item.status()).add(item));
        for (TreeStatus status : TreeStatus.values()) {
            List<TreeItem> items = statusGroups.get(status);
            if (!items.isEmpty()) {
                addStatusGroup(root, status, items);
            }
        }
        restoringTreeState = true;
        try {
            if (tree.getModel() instanceof DefaultTreeModel model) {
                model.setRoot(root);
            } else {
                tree.setModel(new DefaultTreeModel(root));
            }
            rebuildTreeIndex(root);
            restoreExpandedPaths(root);
            if (!effectiveSelection.isBlank()) {
                TreePath selectedPath = findTreePath(root, effectiveSelection);
                if (selectedPath == null && selected != null
                        && (selected.kind() == EntityKind.FIELD || selected.kind() == EntityKind.METHOD)) {
                    DiffNode owner = mappingNode(selected);
                    if (owner != null) {
                        TreePath ownerPath = findTreePath(root, stableNodeKey(owner));
                        if (ownerPath != null) {
                            materializeMembers(ownerPath);
                            selectedPath = findTreePath(root, effectiveSelection);
                        }
                    }
                }
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

    private List<TreeItem> treeItems() {
        return allItems.stream().filter(this::visible).toList();
    }

    private List<TreeItem> allTreeItems() {
        Map<String, TreeItemBuilder> classOwners = new HashMap<>();
        List<TreeItemBuilder> classes = new ArrayList<>();
        List<TreeItem> resources = new ArrayList<>();
        for (DiffNode node : workspace.differences().nodes()) {
            if (node.kind() == EntityKind.CLASS) {
                ClassPair pair = node.left() != null && node.right() != null
                        ? new ClassPair(node.left(), node.right()) : null;
                ClassClassification classification = pair == null ? ClassClassification.AUTO
                        : workspace.overrides().classifications().getOrDefault(pair, ClassClassification.AUTO);
                TreeItemBuilder builder = new TreeItemBuilder(node, classification);
                classes.add(builder);
                if (node.left() != null) classOwners.put("L:" + node.left().name(), builder);
                if (node.right() != null) classOwners.put("R:" + node.right().name(), builder);
            } else if (node.kind() == EntityKind.RESOURCE) {
                TreeStatus status = treeStatus(node);
                resources.add(new TreeItem(node, List.of(), status, status, ClassClassification.AUTO));
            }
        }
        for (DiffNode node : workspace.differences().nodes()) {
            if (node.kind() != EntityKind.FIELD && node.kind() != EntityKind.METHOD) continue;
            TreeItemBuilder owner = node.left() == null ? null : classOwners.get("L:" + node.left().owner());
            if (owner == null && node.right() != null) owner = classOwners.get("R:" + node.right().owner());
            if (owner != null) owner.members.add(node);
        }
        List<TreeItem> result = new ArrayList<>();
        classes.stream().map(TreeItemBuilder::build).forEach(result::add);
        result.addAll(resources);
        return result;
    }

    private void addStatusGroup(DefaultMutableTreeNode root, TreeStatus status, List<TreeItem> items) {
        DefaultMutableTreeNode statusRoot = new DefaultMutableTreeNode(new TreeEntry(
                "status:" + status.key(), text(status.messageKey()) + " (" + items.size() + ')', null));
        root.add(statusRoot);
        if (status == TreeStatus.UNMATCHED) {
            addUnmatchedSides(statusRoot, items);
        } else {
            addTypedItems(statusRoot, status.key(), items);
        }
    }

    private void addUnmatchedSides(DefaultMutableTreeNode statusRoot, List<TreeItem> items) {
        for (TreeSide side : TreeSide.values()) {
            List<TreeItem> sideItems = items.stream().filter(item -> side.matches(item.node())).toList();
            if (sideItems.isEmpty()) continue;
            DefaultMutableTreeNode sideRoot = new DefaultMutableTreeNode(new TreeEntry(
                    "status:unmatched:" + side.key, text(side.messageKey) + " (" + sideItems.size() + ')', null));
            statusRoot.add(sideRoot);
            addTypedItems(sideRoot, "unmatched:" + side.key, sideItems);
        }
    }

    private void addTypedItems(DefaultMutableTreeNode root, String namespace, List<TreeItem> items) {
        List<TreeItem> classes = items.stream().filter(item -> item.node().kind() == EntityKind.CLASS).toList();
        if (!classes.isEmpty()) {
            DefaultMutableTreeNode classRoot = new DefaultMutableTreeNode(new TreeEntry(
                    "status:" + namespace + ":classes", text("tree.classes"), null));
            root.add(classRoot);
            addClasses(namespace, classRoot, classes);
        }
        List<TreeItem> resources = items.stream().filter(item -> item.node().kind() == EntityKind.RESOURCE).toList();
        if (!resources.isEmpty()) {
            DefaultMutableTreeNode resourceRoot = new DefaultMutableTreeNode(new TreeEntry(
                    "status:" + namespace + ":resources", text("tree.resources"), null));
            root.add(resourceRoot);
            addResources(namespace, resourceRoot, resources);
        }
    }

    private void addClasses(String namespace, DefaultMutableTreeNode classRoot, List<TreeItem> items) {
        Map<String, DefaultMutableTreeNode> packages = new HashMap<>();
        String query = search.getText().trim();
        items.forEach(item -> {
            DiffNode node = item.node();
            String className = entityName(node);
            int slash = className.lastIndexOf('/');
            String packageName = slash < 0 ? "" : className.substring(0, slash);
            String simpleName = slash < 0 ? className : className.substring(slash + 1);
            DefaultMutableTreeNode parent = packageNode(classRoot, packages,
                    namespace + ":classes", packageName);
            String classLabel = label(simpleName, node);
            DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(
                    new TreeEntry(stableNodeKey(node), classLabel, node, item.members(), item.classification()));
            parent.add(classNode);
            if (!item.members().isEmpty()) {
                if (query.isEmpty()) {
                    classNode.add(new DefaultMutableTreeNode(new TreeEntry(
                            "placeholder:" + stableNodeKey(node), "…", null)));
                } else {
                    item.members().stream().filter(member -> memberMatches(member, treeSearchPredicate))
                            .forEach(member -> classNode.add(memberNode(member)));
                }
            }
        });
    }

    private static DefaultMutableTreeNode memberNode(DiffNode node) {
        EntityId id = node.left() != null ? node.left() : node.right();
        String marker = node.left() == null ? "+ " : node.right() == null ? "− "
                : node.changed() ? "~ " : "  ";
        return new DefaultMutableTreeNode(new TreeEntry(
                stableNodeKey(node), marker + id.name() + id.descriptor(), node));
    }

    private static void addResources(
            String namespace,
            DefaultMutableTreeNode resourceRoot,
            List<TreeItem> resources) {
        Map<String, DefaultMutableTreeNode> resourceDirectories = new HashMap<>();
        resources.forEach(item -> {
            DiffNode node = item.node();
            String name = entityName(node);
            int slash = name.lastIndexOf('/');
            String directory = slash < 0 ? "" : name.substring(0, slash);
            String simple = slash < 0 ? name : name.substring(slash + 1);
            DefaultMutableTreeNode parent = packageNode(resourceRoot, resourceDirectories,
                    namespace + ":resources", directory);
            parent.add(new DefaultMutableTreeNode(
                    new TreeEntry(stableNodeKey(node), label(simple, node), node)));
        });
    }

    private boolean visible(TreeItem item) {
        DiffNode node = item.node();
        if (!treeSearchPredicate.test(node.displayName())
                && item.members().stream().noneMatch(member -> memberMatches(member, treeSearchPredicate))) {
            return false;
        }
        FilterOption option = (FilterOption) filter.getSelectedItem();
        FilterKind kind = option == null ? FilterKind.ALL : option.kind();
        return switch (kind) {
            case ALL -> true;
            case CHANGED -> item.status() == TreeStatus.CHANGED;
            case ADDED -> hasChange(item, ChangeKind.ADDED);
            case REMOVED -> hasChange(item, ChangeKind.REMOVED);
            case MODIFIED -> item.status() == TreeStatus.CHANGED;
            case UNRESOLVED -> hasChange(item, ChangeKind.UNRESOLVED);
        };
    }

    private Predicate<String> prepareSearchPredicate() {
        String query = search.getText().trim();
        if (query.isEmpty()) {
            return ignored -> true;
        }
        return SearchMatcher.CONTAINS.predicate(query);
    }

    private static boolean memberMatches(DiffNode member, Predicate<String> matcher) {
        return matcher.test(member.displayName())
                || (member.left() != null && matcher.test(member.left().externalName()))
                || (member.right() != null && matcher.test(member.right().externalName()));
    }

    private static boolean hasChange(TreeItem item, ChangeKind kind) {
        return item.node().changes().contains(kind)
                || item.members().stream().anyMatch(member -> member.changes().contains(kind));
    }

    private static TreeStatus treeStatus(DiffNode node) {
        if (node.resolution() != ResolutionStatus.NONE) {
            return TreeStatus.CHANGED;
        }
        if (node.left() == null || node.right() == null || node.changes().contains(ChangeKind.UNRESOLVED)) {
            return TreeStatus.UNMATCHED;
        }
        return hasSubstantiveChange(node) ? TreeStatus.CHANGED : TreeStatus.UNCHANGED;
    }

    private static boolean hasSubstantiveChange(DiffNode node) {
        return node.changes().stream().anyMatch(change -> change != ChangeKind.RENAMED);
    }

    static int detailTabForSelection(EntityKind kind, int activeTab) {
        boolean member = kind == EntityKind.FIELD || kind == EntityKind.METHOD;
        return member && (activeTab < STRUCTURE_TAB || activeTab > SOURCE_TAB) ? SOURCE_TAB : activeTab;
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
                    formatClass(workspace.right().classes().get(rightClass)),
                    MemberTextLocator.structure(leftId), MemberTextLocator.structure(rightId));
            int activeTab = tabs.getSelectedIndex();
            boolean member = node.kind() == EntityKind.FIELD || node.kind() == EntityKind.METHOD;
            int targetTab = detailTabForSelection(node.kind(), activeTab);
            if (targetTab != activeTab) {
                source.setLoading(text("detail.loading"));
                tabs.setSelectedIndex(targetTab);
            } else if (activeTab == BYTECODE_TAB) {
                loadBytecode();
            } else if (activeTab == SOURCE_TAB) {
                loadSource();
            } else if (!member) {
                loadBytecode();
                source.setTexts(text("detail.select"), text("detail.select"));
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

    public void navigateToSearchHit(WorkspaceController.SearchHit hit) {
        if (hit == null || hit.entity() == null) return;
        DiffNode node = workspace.differences().nodes().stream()
                .filter(candidate -> hit.leftSide()
                        ? hit.entity().equals(candidate.left()) : hit.entity().equals(candidate.right()))
                .findFirst()
                .orElseGet(() -> workspace.differences().nodes().stream()
                        .filter(candidate -> candidate.kind() == EntityKind.CLASS
                                && (hit.entity().owner().equals(candidate.left() == null ? "" : candidate.left().name())
                                || hit.entity().owner().equals(candidate.right() == null ? "" : candidate.right().name())))
                        .findFirst().orElse(null));
        if (node == null) return;
        selectNode(node);
        if (hit.type() == WorkspaceController.SearchType.TEXT) {
            if (hit.entity().kind() == EntityKind.RESOURCE) {
                tabs.setSelectedIndex(STRUCTURE_TAB);
                structure.revealLine(hit.leftSide(), hit.line());
            } else {
                tabs.setSelectedIndex(SOURCE_TAB);
                source.revealLine(hit.leftSide(), hit.line());
            }
        }
    }

    private void loadBytecode() {
        DiffNode node = selected;
        if (node == null || node.kind() == EntityKind.RESOURCE) {
            bytecode.setTexts("", "");
            return;
        }
        long requestGeneration = generation;
        bytecode.setLoading(text("detail.loading"));
        loadPair(
                callback -> controller.loadBytecode(node, true, semantic.isSelected(), callback, errorHandler),
                callback -> controller.loadBytecode(node, false, semantic.isSelected(), callback, errorHandler),
                bytecode,
                requestGeneration,
                MemberTextLocator.bytecode(node.left()),
                MemberTextLocator.bytecode(node.right()));
    }

    private void loadSource() {
        DiffNode node = selected;
        if (node == null || node.kind() == EntityKind.RESOURCE || sourceGeneration == generation) {
            return;
        }
        sourceGeneration = generation;
        long requestGeneration = generation;
        source.setLoading(text("detail.loading"));
        CanonicalNamesDirection direction = canonicalNamesDirection();
        loadPair(
                callback -> controller.loadSource(node, true, direction, callback, errorHandler),
                callback -> controller.loadSource(node, false, direction, callback, errorHandler),
                source,
                requestGeneration,
                sourceLocator(node, true),
                sourceLocator(node, false));
    }

    private TextLocator sourceLocator(DiffNode node, boolean leftSide) {
        EntityId id = leftSide ? node.left() : node.right();
        if (canonicalNamesDirection().remaps(leftSide) && node.left() != null && node.right() != null) {
            id = leftSide ? node.right() : node.left();
        }
        return MemberTextLocator.source(id);
    }

    private void loadPair(
            Consumer<Consumer<String>> leftLoader,
            Consumer<Consumer<String>> rightLoader,
            SideBySideTextPanel target,
            long requestGeneration) {
        loadPair(leftLoader, rightLoader, target, requestGeneration, null, null);
    }

    private void loadPair(
            Consumer<Consumer<String>> leftLoader,
            Consumer<Consumer<String>> rightLoader,
            SideBySideTextPanel target,
            long requestGeneration,
            TextLocator leftLocator,
            TextLocator rightLocator) {
        AtomicReference<String> leftText = new AtomicReference<>("");
        AtomicReference<String> rightText = new AtomicReference<>("");
        AtomicInteger completed = new AtomicInteger();
        Runnable render = () -> {
            if (completed.incrementAndGet() == 2 && generation == requestGeneration) {
                target.setTexts(leftText.get(), rightText.get(), leftLocator, rightLocator);
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
        DiffNode subject = mappingNode(node);
        if (subject == null || subject.left() == null) {
            candidateModel.setRows(List.of());
            updateCandidateActions();
            return;
        }
        EntityId left = subject.left();
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
        DiffNode subject = mappingNode(selected);
        if (subject != null && subject.left() != null) {
            runMappingOperation(() -> controller.restoreAutomatic(
                    subject.left(), this::mappingChanged, this::mappingFailed));
        }
    }

    private void toggleSingleSidedResolution() {
        DiffNode subject = mappingNode(selected);
        if (subject == null || (subject.left() == null) == (subject.right() == null)) {
            return;
        }
        if (subject.resolution() == ResolutionStatus.NONE) {
            runMappingOperation(() -> controller.confirmSingleSided(
                    subject, this::mappingChanged, this::mappingFailed));
        } else {
            runMappingOperation(() -> controller.clearSingleSidedResolution(
                    subject, this::mappingChanged, this::mappingFailed));
        }
    }

    private void restoreCurrentToPending() {
        DiffNode subject = mappingNode(selected);
        if (subject != null && subject.left() != null && subject.right() != null) {
            runMappingOperation(() -> controller.detachMapping(subject, this::mappingChanged, this::mappingFailed));
        }
    }

    private void runMappingOperation(Runnable operation) {
        if (mappingBusy) return;
        mappingBusy = true;
        operationProgress.setVisible(true);
        updateCandidateActions();
        operation.run();
    }

    private void mappingFailed(Throwable throwable) {
        mappingBusy = false;
        operationProgress.setVisible(false);
        updateCandidateActions();
        errorHandler.accept(throwable);
    }

    private void showTreeMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        TreePath path = popupPathAt(tree, event.getX(), event.getY());
        if (path == null) return;
        tree.setSelectionPath(path);
        java.util.Optional<TreeEntry> entry = treeEntry(path);
        if (entry.isEmpty()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        if (entry.get().node() == null) {
            TreeSide side = sideForKey(entry.get().stableKey());
            List<DiffNode> descendants = collectSingleSided((DefaultMutableTreeNode) path.getLastPathComponent());
            if (side != null && !descendants.isEmpty()) {
                JMenuItem batch = new JMenuItem(text(side == TreeSide.OLD
                        ? "resolution.confirmDirectoryRemoved" : "resolution.confirmDirectoryAdded",
                        descendants.size()));
                batch.addActionListener(ignored -> confirmDirectory(descendants, side));
                menu.add(batch);
            }
            if (menu.getComponentCount() > 0) menu.show(tree, event.getX(), event.getY());
            return;
        }
        DiffNode node = entry.get().node();
        if (node.kind() == EntityKind.FIELD || node.kind() == EntityKind.METHOD) return;
        if ((node.left() == null) != (node.right() == null)) {
            JMenuItem item = new JMenuItem(resolutionLabel(node));
            item.addActionListener(ignored -> toggleSingleSidedResolution());
            menu.add(item);
            EntityId id = node.left() != null ? node.left() : node.right();
            if (workspace.detachedPairs().stream().anyMatch(pair -> pair.involves(id))) {
                JMenuItem automatic = new JMenuItem(text("candidate.restoreAutomatic"));
                automatic.addActionListener(ignored -> runMappingOperation(() -> controller.restoreDetached(
                        id, this::mappingChanged, this::mappingFailed)));
                menu.add(automatic);
            }
        } else if (node.left() != null) {
            JMenuItem restore = new JMenuItem(text("candidate.restorePending"));
            restore.addActionListener(ignored -> restoreCurrentToPending());
            menu.add(restore);
            if (workspace.lockedMappings().containsKey(node.left())) {
                JMenuItem automatic = new JMenuItem(text("candidate.restoreAutomatic"));
                automatic.addActionListener(ignored -> unlockSelected());
                menu.add(automatic);
            }
            if (node.kind() == EntityKind.CLASS) {
                if (menu.getComponentCount() > 0) menu.addSeparator();
                addClassificationActions(menu, node);
            }
        }
        if (menu.getComponentCount() > 0) menu.show(tree, event.getX(), event.getY());
    }

    static TreePath popupPathAt(JTree tree, int x, int y) {
        int row = tree.getClosestRowForLocation(x, y);
        if (row < 0) return null;
        Rectangle bounds = tree.getRowBounds(row);
        if (bounds == null || y < bounds.y || y >= bounds.y + bounds.height) return null;
        return tree.getPathForRow(row);
    }

    private void addClassificationActions(JPopupMenu menu, DiffNode node) {
        ClassPair pair = new ClassPair(node.left(), node.right());
        ClassClassification current = workspace.overrides().classifications()
                .getOrDefault(pair, ClassClassification.AUTO);
        if (current != ClassClassification.FORCE_CHANGED) {
            JMenuItem changed = new JMenuItem(text("classification.forceChanged"));
            changed.addActionListener(ignored -> classify(node, ClassClassification.FORCE_CHANGED));
            menu.add(changed);
        }
        if (current != ClassClassification.FORCE_UNCHANGED) {
            JMenuItem unchanged = new JMenuItem(text("classification.forceUnchanged"));
            unchanged.addActionListener(ignored -> classify(node, ClassClassification.FORCE_UNCHANGED));
            menu.add(unchanged);
        }
        if (current != ClassClassification.AUTO) {
            JMenuItem automatic = new JMenuItem(text("classification.restoreAutomatic"));
            automatic.addActionListener(ignored -> classify(node, ClassClassification.AUTO));
            menu.add(automatic);
        }
    }

    private void classify(DiffNode node, ClassClassification classification) {
        runMappingOperation(() -> controller.classifyClass(
                node, classification, this::mappingChanged, this::mappingFailed));
    }

    private void confirmDirectory(List<DiffNode> nodes, TreeSide side) {
        int answer = JOptionPane.showConfirmDialog(this,
                text(side == TreeSide.OLD ? "dialog.confirmDirectoryRemoved" : "dialog.confirmDirectoryAdded",
                        nodes.size()),
                text("dialog.confirmDirectoryTitle"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            runMappingOperation(() -> controller.confirmSingleSided(
                    nodes, this::mappingChanged, this::mappingFailed));
        }
    }

    private static TreeSide sideForKey(String key) {
        if (key.contains("unmatched:old")) return TreeSide.OLD;
        if (key.contains("unmatched:new")) return TreeSide.NEW;
        return null;
    }

    private static List<DiffNode> collectSingleSided(DefaultMutableTreeNode root) {
        Map<String, DiffNode> result = new java.util.LinkedHashMap<>();
        collectSingleSided(root, result);
        return List.copyOf(result.values());
    }

    private static void collectSingleSided(DefaultMutableTreeNode node, Map<String, DiffNode> target) {
        if (node.getUserObject() instanceof TreeEntry entry && entry.node() != null) {
            DiffNode diff = entry.node();
            if ((diff.kind() == EntityKind.CLASS || diff.kind() == EntityKind.RESOURCE)
                    && (diff.left() == null) != (diff.right() == null)) {
                target.put(stableNodeKey(diff), diff);
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectSingleSided((DefaultMutableTreeNode) node.getChildAt(index), target);
        }
    }

    private static String resolutionLabel(DiffNode node) {
        if (node.resolution() != ResolutionStatus.NONE) {
            return text("candidate.restorePending");
        }
        return node.left() == null ? text("resolution.confirmAdded") : text("resolution.confirmRemoved");
    }

    private void chooseManual() {
        DiffNode subject = mappingNode(selected);
        if (subject == null || subject.left() == null || subject.kind() == EntityKind.RESOURCE) {
            return;
        }
        EntityId left = subject.left();
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
        runMappingOperation(() -> controller.confirmMapping(decision, this::mappingChanged, this::mappingFailed));
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
        DiffNode subject = mappingNode(selected);
        int viewRow = candidates.getSelectedRow();
        CandidateRow row = viewRow < 0 ? null
                : candidateModel.row(candidates.convertRowIndexToModel(viewRow));
        boolean alreadyConfirmed = row != null && row.current()
                && row.decision().status() == MatchStatus.MANUAL_CONFIRMED;
        lockCandidate.setVisible(row != null && !alreadyConfirmed);
        lockCandidate.setEnabled(row != null && !alreadyConfirmed);
        boolean manual = subject != null && subject.left() != null
                && workspace.lockedMappings().containsKey(subject.left());
        unlockCandidate.setVisible(manual);
        unlockCandidate.setEnabled(manual);
        boolean canChoose = subject != null && subject.left() != null && subject.kind() == EntityKind.CLASS;
        manualCandidate.setVisible(canChoose);
        manualCandidate.setEnabled(canChoose);
        boolean singleSided = subject != null && (subject.left() == null) != (subject.right() == null);
        resolutionAction.setVisible(singleSided);
        resolutionAction.setEnabled(singleSided);
        if (singleSided) {
            resolutionAction.setText(resolutionLabel(subject));
        }
        boolean paired = subject != null && subject.left() != null && subject.right() != null
                && (subject.kind() == EntityKind.CLASS || subject.kind() == EntityKind.RESOURCE);
        restorePending.setVisible(paired);
        restorePending.setEnabled(paired);
        if (mappingBusy) {
            lockCandidate.setEnabled(false);
            unlockCandidate.setEnabled(false);
            manualCandidate.setEnabled(false);
            resolutionAction.setEnabled(false);
            restorePending.setEnabled(false);
        }
    }

    private boolean ownersCompatible(EntityId left, EntityId right) {
        if (left.kind() == EntityKind.CLASS) {
            return true;
        }
        EntityId mappedOwner = workspace.matches().confirmedMappings().get(EntityId.classId(left.owner()));
        return EntityId.classId(right.owner()).equals(mappedOwner);
    }

    private void mappingChanged(WorkspaceController.Workspace updated) {
        mappingBusy = false;
        operationProgress.setVisible(false);
        WorkspaceController.AnalysisUpdate update = controller.latestAnalysisUpdate();
        FilterOption selectedFilter = (FilterOption) filter.getSelectedItem();
        boolean unfiltered = search.getText().isBlank()
                && (selectedFilter == null || selectedFilter.kind() == FilterKind.ALL);
        if (update != null && update.workspace() == updated && !update.fullRefresh()
                && update.affectedEntities().size() <= 1_000 && unfiltered && !treeNodeIndex.isEmpty()) {
            applyIncrementalTreeUpdate(update);
        } else {
            refresh(updated);
        }
    }

    private void applyIncrementalTreeUpdate(WorkspaceController.AnalysisUpdate update) {
        long started = System.nanoTime();
        List<TreeItem> previousItems = allItems;
        workspace = update.workspace();
        Set<EntityId> affected = update.affectedEntities();
        List<TreeItem> oldItems = previousItems.stream().filter(item -> itemAffected(item, affected)).toList();
        List<TreeItem> newItems = treeItemsForAffected(affected);
        List<TreeItem> mergedItems = new ArrayList<>(previousItems.size() - oldItems.size() + newItems.size());
        previousItems.stream().filter(item -> !itemAffected(item, affected)).forEach(mergedItems::add);
        mergedItems.addAll(newItems);
        allItems = List.copyOf(mergedItems);
        DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        String selectedKey = selected == null ? "" : stableNodeKey(selected);

        for (TreeItem item : oldItems) {
            DefaultMutableTreeNode node = treeNodeIndex.get(stableNodeKey(item.node()));
            if (node != null && node.getParent() != null) removeIndexedNode(node, root, model);
        }
        for (TreeItem item : newItems) {
            if (visible(item)) insertTreeItem(root, item, model);
        }
        updateTreeGroupLabels(model);
        updateSummary();
        restoreAffectedExpansion(newItems);
        restoreSelection(selectedKey);
        long treeMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        LOGGER.info("Incremental tree update: tree={}ms, affected={}, matching={}ms, diff={}ms",
                treeMillis, affected.size(), update.timings().matchingMillis(), update.timings().diffMillis());
    }

    private boolean itemAffected(TreeItem item, Set<EntityId> affected) {
        DiffNode node = item.node();
        if (entityAffected(node.left(), affected) || entityAffected(node.right(), affected)) return true;
        return item.members().stream().anyMatch(member -> entityAffected(member.left(), affected)
                || entityAffected(member.right(), affected));
    }

    private List<TreeItem> treeItemsForAffected(Set<EntityId> affected) {
        Map<String, TreeItemBuilder> classOwners = new HashMap<>();
        List<TreeItemBuilder> classes = new ArrayList<>();
        List<TreeItem> resources = new ArrayList<>();
        for (DiffNode node : workspace.differences().nodes()) {
            if (node.kind() == EntityKind.CLASS
                    && (entityAffected(node.left(), affected) || entityAffected(node.right(), affected))) {
                ClassClassification classification = node.left() != null && node.right() != null
                        ? workspace.overrides().classifications().getOrDefault(
                                new ClassPair(node.left(), node.right()), ClassClassification.AUTO)
                        : ClassClassification.AUTO;
                TreeItemBuilder builder = new TreeItemBuilder(node, classification);
                classes.add(builder);
                if (node.left() != null) classOwners.put("L:" + node.left().name(), builder);
                if (node.right() != null) classOwners.put("R:" + node.right().name(), builder);
            } else if (node.kind() == EntityKind.RESOURCE
                    && (entityAffected(node.left(), affected) || entityAffected(node.right(), affected))) {
                TreeStatus status = treeStatus(node);
                resources.add(new TreeItem(node, List.of(), status, status, ClassClassification.AUTO));
            }
        }
        if (!classOwners.isEmpty()) {
            for (DiffNode node : workspace.differences().nodes()) {
                if (node.kind() != EntityKind.FIELD && node.kind() != EntityKind.METHOD) continue;
                TreeItemBuilder owner = node.left() == null ? null : classOwners.get("L:" + node.left().owner());
                if (owner == null && node.right() != null) owner = classOwners.get("R:" + node.right().owner());
                if (owner != null) owner.members.add(node);
            }
        }
        List<TreeItem> result = new ArrayList<>(classes.size() + resources.size());
        classes.stream().map(TreeItemBuilder::build).forEach(result::add);
        result.addAll(resources);
        return result;
    }

    private static boolean entityAffected(EntityId entity, Set<EntityId> affected) {
        if (entity == null) return false;
        if (affected.contains(entity)) return true;
        if (entity.kind() == EntityKind.FIELD || entity.kind() == EntityKind.METHOD) {
            return affected.contains(EntityId.classId(entity.owner()));
        }
        if (entity.kind() == EntityKind.CLASS) {
            return affected.stream().anyMatch(id -> (id.kind() == EntityKind.FIELD || id.kind() == EntityKind.METHOD)
                    && id.owner().equals(entity.name()));
        }
        return false;
    }

    private void insertTreeItem(DefaultMutableTreeNode root, TreeItem item, DefaultTreeModel model) {
        DefaultMutableTreeNode temporaryRoot = new DefaultMutableTreeNode("root");
        addStatusGroup(temporaryRoot, item.status(), List.of(item));
        if (temporaryRoot.getChildCount() > 0) {
            mergeTreeBranch(root, (DefaultMutableTreeNode) temporaryRoot.getChildAt(0), model);
        }
    }

    private void mergeTreeBranch(
            DefaultMutableTreeNode parent, DefaultMutableTreeNode desired, DefaultTreeModel model) {
        String key = nodeKey(desired);
        DefaultMutableTreeNode existing = treeNodeIndex.get(key);
        if (existing == null || existing.getParent() != parent) {
            int index = insertionIndex(parent, desired);
            model.insertNodeInto(desired, parent, index);
            indexSubtree(desired);
            return;
        }
        existing.setUserObject(desired.getUserObject());
        model.nodeChanged(existing);
        List<DefaultMutableTreeNode> desiredChildren = new ArrayList<>();
        for (int index = 0; index < desired.getChildCount(); index++) {
            desiredChildren.add((DefaultMutableTreeNode) desired.getChildAt(index));
        }
        for (DefaultMutableTreeNode child : desiredChildren) mergeTreeBranch(existing, child, model);
    }

    private int insertionIndex(DefaultMutableTreeNode parent, DefaultMutableTreeNode desired) {
        String desiredKey = nodeKey(desired);
        if (parent.getParent() == null && desiredKey.startsWith("status:")) {
            int desiredOrder = statusOrder(desiredKey);
            for (int index = 0; index < parent.getChildCount(); index++) {
                if (statusOrder(nodeKey((DefaultMutableTreeNode) parent.getChildAt(index))) > desiredOrder) return index;
            }
            return parent.getChildCount();
        }
        String desiredLabel = desired.toString();
        for (int index = 0; index < parent.getChildCount(); index++) {
            if (desiredLabel.compareToIgnoreCase(parent.getChildAt(index).toString()) < 0) return index;
        }
        return parent.getChildCount();
    }

    private static int statusOrder(String key) {
        if (key.equals("status:changed")) return 0;
        if (key.equals("status:unmatched")) return 1;
        if (key.equals("status:unchanged")) return 2;
        return 3;
    }

    private void removeIndexedNode(
            DefaultMutableTreeNode node, DefaultMutableTreeNode root, DefaultTreeModel model) {
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        unindexSubtree(node);
        model.removeNodeFromParent(node);
        while (parent != null && parent != root && parent.getChildCount() == 0) {
            DefaultMutableTreeNode empty = parent;
            parent = (DefaultMutableTreeNode) empty.getParent();
            unindexSubtree(empty);
            model.removeNodeFromParent(empty);
        }
    }

    private void updateTreeGroupLabels(DefaultTreeModel model) {
        Map<TreeStatus, Long> counts = new EnumMap<>(TreeStatus.class);
        for (TreeStatus status : TreeStatus.values()) counts.put(status, 0L);
        allItems.forEach(item -> counts.compute(item.status(), (ignored, value) -> value + 1));
        for (TreeStatus status : TreeStatus.values()) {
            DefaultMutableTreeNode node = treeNodeIndex.get("status:" + status.key());
            if (node != null && node.getUserObject() instanceof TreeEntry entry) {
                node.setUserObject(new TreeEntry(entry.stableKey(),
                        text(status.messageKey()) + " (" + counts.get(status) + ')', null));
                model.nodeChanged(node);
            }
        }
        for (TreeSide side : TreeSide.values()) {
            DefaultMutableTreeNode node = treeNodeIndex.get("status:unmatched:" + side.key);
            if (node != null && node.getUserObject() instanceof TreeEntry entry) {
                long count = allItems.stream().filter(item -> item.status() == TreeStatus.UNMATCHED)
                        .filter(item -> side.matches(item.node())).count();
                node.setUserObject(new TreeEntry(entry.stableKey(), text(side.messageKey) + " (" + count + ')', null));
                model.nodeChanged(node);
            }
        }
    }

    private void updateSummary() {
        long confirmedClasses = workspace.matches().confirmed().stream()
                .filter(match -> match.left().kind() == EntityKind.CLASS)
                .count();
        long pendingClasses = workspace.matches().candidates().keySet().stream()
                .filter(entity -> entity.kind() == EntityKind.CLASS)
                .count();
        long changedClasses = allItems.stream()
                .filter(item -> item.node().kind() == EntityKind.CLASS)
                .filter(item -> item.status() == TreeStatus.CHANGED)
                .count();
        summary.update(workspace.left().classes().size(), workspace.right().classes().size(),
                confirmedClasses, pendingClasses, changedClasses);
    }

    private void restoreAffectedExpansion(List<TreeItem> items) {
        for (TreeItem item : items) {
            DefaultMutableTreeNode node = treeNodeIndex.get(stableNodeKey(item.node()));
            if (node == null) continue;
            TreePath path = new TreePath(node.getPath());
            for (Object component : path.getPath()) {
                if (component instanceof DefaultMutableTreeNode treeNode
                        && expandedTreeKeys.contains(nodeKey(treeNode))) {
                    tree.expandPath(new TreePath(treeNode.getPath()));
                }
            }
        }
    }

    private void restoreSelection(String key) {
        if (key.isBlank()) return;
        DefaultMutableTreeNode node = treeNodeIndex.get(key);
        if (node != null) tree.setSelectionPath(new TreePath(node.getPath()));
    }

    private void rebuildTreeIndex(DefaultMutableTreeNode root) {
        treeNodeIndex.clear();
        indexSubtree(root);
    }

    private void indexSubtree(DefaultMutableTreeNode node) {
        String key = nodeKey(node);
        if (!key.isBlank()) treeNodeIndex.put(key, node);
        for (int index = 0; index < node.getChildCount(); index++) {
            indexSubtree((DefaultMutableTreeNode) node.getChildAt(index));
        }
    }

    private void unindexSubtree(DefaultMutableTreeNode node) {
        treeNodeIndex.remove(nodeKey(node));
        for (int index = 0; index < node.getChildCount(); index++) {
            unindexSubtree((DefaultMutableTreeNode) node.getChildAt(index));
        }
    }

    private static String nodeKey(DefaultMutableTreeNode node) {
        return node.getUserObject() instanceof TreeEntry entry ? entry.stableKey() : "";
    }

    private DiffNode mappingNode(DiffNode node) {
        if (node == null || node.kind() == EntityKind.CLASS || node.kind() == EntityKind.RESOURCE) {
            return node;
        }
        EntityId id = node.left() != null ? node.left() : node.right();
        boolean left = node.left() != null;
        return workspace.differences().nodes().stream()
                .filter(candidate -> candidate.kind() == EntityKind.CLASS)
                .filter(candidate -> {
                    EntityId classId = left ? candidate.left() : candidate.right();
                    return classId != null && classId.name().equals(id.owner());
                })
                .findFirst().orElse(null);
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
        if (user instanceof TreeEntry entry
                && expandedTreeKeys.contains(expansionStateKey(entry.stableKey()))) {
            tree.expandPath(new TreePath(node.getPath()));
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            restoreExpandedPaths((DefaultMutableTreeNode) children.nextElement());
        }
    }

    private static Set<String> migrateExpandedTreeKeys(Set<String> stored) {
        Set<String> migrated = new LinkedHashSet<>();
        for (String key : stored) {
            if (key.equals("root:classes")) {
                for (TreeStatus status : TreeStatus.values()) {
                    migrated.add("status:" + status.key());
                    if (status == TreeStatus.UNMATCHED) {
                        migrated.add("status:unmatched:old");
                        migrated.add("status:unmatched:old:classes");
                        migrated.add("status:unmatched:new");
                        migrated.add("status:unmatched:new:classes");
                    } else {
                        migrated.add("status:" + status.key() + ":classes");
                    }
                }
            } else if (key.equals("root:resources")) {
                for (TreeStatus status : TreeStatus.values()) {
                    migrated.add("status:" + status.key());
                    if (status == TreeStatus.UNMATCHED) {
                        migrated.add("status:unmatched:old");
                        migrated.add("status:unmatched:old:resources");
                        migrated.add("status:unmatched:new");
                        migrated.add("status:unmatched:new:resources");
                    } else {
                        migrated.add("status:" + status.key() + ":resources");
                    }
                }
            } else if (key.startsWith("package:classes:")) {
                String path = key.substring("package:classes:".length());
                migrated.add("package:*:classes:" + path);
            } else if (key.startsWith("package:resources:")) {
                String path = key.substring("package:resources:".length());
                migrated.add("package:*:resources:" + path);
            } else {
                migrated.add(expansionStateKey(key));
            }
        }
        return migrated;
    }

    private static String expansionStateKey(String stableKey) {
        if (stableKey.startsWith("entity:L:CLASS:")) {
            return "owner:*:L:" + stableKey.substring("entity:L:CLASS:".length());
        }
        if (stableKey.startsWith("entity:R:CLASS:")) {
            return "owner:*:R:" + stableKey.substring("entity:R:CLASS:".length());
        }
        if (stableKey.startsWith("package:")) {
            String value = stableKey.substring("package:".length());
            int classes = value.indexOf(":classes:");
            if (classes >= 0) return "package:*:classes:" + value.substring(classes + ":classes:".length());
            int resources = value.indexOf(":resources:");
            if (resources >= 0) return "package:*:resources:" + value.substring(resources + ":resources:".length());
        }
        if (stableKey.startsWith("owner:")) {
            String[] parts = stableKey.split(":", 4);
            if (parts.length == 4 && isStatusKey(parts[1])) {
                return "owner:*:" + parts[2] + ':' + parts[3];
            }
        }
        if (stableKey.startsWith("member-owner:")) {
            String[] parts = stableKey.split(":", 4);
            if (parts.length == 4 && isStatusKey(parts[1])) {
                return "owner:*:" + parts[2] + ':' + parts[3];
            }
        }
        return stableKey;
    }

    private static boolean isStatusKey(String value) {
        return value.equals("changed") || value.equals("unmatched") || value.equals("unchanged");
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

    int summaryCardCountForTesting() {
        return summary.getComponentCount();
    }

    List<Long> summaryValuesForTesting() {
        return summary.values();
    }

    List<String> canonicalNameOptionsForTesting() {
        List<String> options = new ArrayList<>();
        for (int index = 0; index < canonicalNames.getItemCount(); index++) {
            options.add(canonicalNames.getItemAt(index).toString());
        }
        return options;
    }

    EntityId mappingSubjectForTesting(DiffNode node) {
        DiffNode subject = mappingNode(node);
        return subject == null ? null : subject.left() != null ? subject.left() : subject.right();
    }

    boolean mappingBusyForTesting() {
        return mappingBusy && operationProgress.isVisible();
    }

    void expandEntityForTesting(String stableKey) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        TreePath path = findTreePath(root, stableKey);
        if (path != null) tree.expandPath(path);
    }

    void selectForTesting(DiffNode node) {
        selected = node;
        updateCandidates(node);
    }

    List<String> rootKeysForTesting() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < root.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(index);
            if (child.getUserObject() instanceof TreeEntry entry) keys.add(entry.stableKey());
        }
        return keys;
    }

    List<String> stableTreeKeysForTesting() {
        List<String> keys = new ArrayList<>();
        collectStableKeys((DefaultMutableTreeNode) tree.getModel().getRoot(), keys);
        return keys;
    }

    String treeLabelForKeyForTesting(String key) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        TreePath path = findTreePath(root, key);
        if (path == null) return null;
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof TreeEntry entry ? entry.label() : String.valueOf(value);
    }

    String treeTooltipForKeyForTesting(String key) {
        DefaultMutableTreeNode node = treeNodeIndex.get(key);
        if (node == null) return null;
        TreePath path = new TreePath(node.getPath());
        int row = tree.getRowForPath(path);
        Component component = tree.getCellRenderer().getTreeCellRendererComponent(
                tree, node, false, tree.isExpanded(path), node.isLeaf(), row, false);
        return component instanceof javax.swing.JComponent swingComponent
                ? swingComponent.getToolTipText() : null;
    }

    void applyAnalysisUpdateForTesting(WorkspaceController.AnalysisUpdate update) {
        applyIncrementalTreeUpdate(update);
    }

    private static void collectStableKeys(DefaultMutableTreeNode node, List<String> target) {
        if (node.getUserObject() instanceof TreeEntry entry) target.add(entry.stableKey());
        for (int index = 0; index < node.getChildCount(); index++) {
            collectStableKeys((DefaultMutableTreeNode) node.getChildAt(index), target);
        }
    }

    private enum TreeStatus {
        CHANGED("changed", "tree.changed"),
        UNMATCHED("unmatched", "tree.unmatched"),
        UNCHANGED("unchanged", "tree.unchanged");

        private final String key;
        private final String messageKey;

        TreeStatus(String key, String messageKey) {
            this.key = key;
            this.messageKey = messageKey;
        }

        String key() { return key; }
        String messageKey() { return messageKey; }
    }

    private enum FilterKind { ALL, CHANGED, ADDED, REMOVED, MODIFIED, UNRESOLVED }

    private record FilterOption(String label, FilterKind kind) {
        @Override public String toString() { return label; }
    }

    private record CanonicalNameOption(String label, CanonicalNamesDirection direction) {
        @Override public String toString() { return label; }
    }

    private record TreeItem(
            DiffNode node,
            List<DiffNode> members,
            TreeStatus automaticStatus,
            TreeStatus status,
            ClassClassification classification) {
        TreeItem {
            members = List.copyOf(members);
        }
    }

    private static final class TreeItemBuilder {
        private final DiffNode node;
        private final ClassClassification classification;
        private final List<DiffNode> members = new ArrayList<>();

        TreeItemBuilder(DiffNode node, ClassClassification classification) {
            this.node = node;
            this.classification = classification;
        }

        TreeItem build() {
            members.sort(Comparator.comparing(DiffNode::displayName));
            TreeStatus status = treeStatus(node);
            if (status == TreeStatus.UNMATCHED && node.left() != null && node.right() != null) {
                status = TreeStatus.CHANGED;
            }
            TreeStatus automaticStatus = status;
            if (node.left() != null && node.right() != null) {
                if (classification == ClassClassification.FORCE_CHANGED) status = TreeStatus.CHANGED;
                if (classification == ClassClassification.FORCE_UNCHANGED) status = TreeStatus.UNCHANGED;
            }
            return new TreeItem(node, members, automaticStatus, status, classification);
        }
    }

    private enum TreeSide {
        OLD("old", "tree.oldVersion") {
            @Override boolean matches(DiffNode node) { return node.left() != null && node.right() == null; }
        },
        NEW("new", "tree.newVersion") {
            @Override boolean matches(DiffNode node) { return node.left() == null && node.right() != null; }
        };

        private final String key;
        private final String messageKey;

        TreeSide(String key, String messageKey) {
            this.key = key;
            this.messageKey = messageKey;
        }

        abstract boolean matches(DiffNode node);
    }

    private record TreeEntry(
            String stableKey,
            String label,
            DiffNode node,
            List<DiffNode> members,
            ClassClassification classification) {
        TreeEntry {
            members = members == null ? List.of() : List.copyOf(members);
            classification = classification == null ? ClassClassification.AUTO : classification;
        }

        TreeEntry(String stableKey, String label, DiffNode node) {
            this(stableKey, label, node, List.of(), ClassClassification.AUTO);
        }

        TreeEntry(String stableKey, String label, DiffNode node, List<DiffNode> members) {
            this(stableKey, label, node, members, ClassClassification.AUTO);
        }

        @Override public String toString() { return label; }
    }

    private static final class WorkspaceSummary extends JPanel {
        private final MetricCard oldClasses = new MetricCard(text("workspace.metric.oldClasses"));
        private final MetricCard newClasses = new MetricCard(text("workspace.metric.newClasses"));
        private final MetricCard confirmed = new MetricCard(text("workspace.metric.confirmed"));
        private final MetricCard pending = new MetricCard(text("workspace.metric.pending"));
        private final MetricCard changed = new MetricCard(text("workspace.metric.changed"));

        WorkspaceSummary() {
            super(new FlowLayout(FlowLayout.LEFT, 8, 0));
            setOpaque(false);
            add(oldClasses);
            add(newClasses);
            add(confirmed);
            add(pending);
            add(changed);
        }

        void update(long oldCount, long newCount, long confirmedCount, long pendingCount, long changedCount) {
            oldClasses.setValue(oldCount);
            newClasses.setValue(newCount);
            confirmed.setValue(confirmedCount);
            pending.setValue(pendingCount);
            changed.setValue(changedCount);
        }

        List<Long> values() {
            return List.of(oldClasses.value(), newClasses.value(), confirmed.value(), pending.value(), changed.value());
        }
    }

    private static final class MetricCard extends JPanel {
        private final JLabel value = new JLabel("0");

        MetricCard(String label) {
            super(new BorderLayout(7, 0));
            setOpaque(true);
            setBackground(UIManager.getColor("TextField.background"));
            Color line = UIManager.getColor("Separator.foreground");
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(line == null ? Color.GRAY : line),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));
            value.setFont(value.getFont().deriveFont(Font.BOLD));
            JLabel description = new JLabel(label);
            description.setForeground(UIManager.getColor("Label.disabledForeground"));
            add(value, BorderLayout.WEST);
            add(description, BorderLayout.CENTER);
        }

        void setValue(long number) {
            value.setText(Long.toString(number));
        }

        long value() {
            return Long.parseLong(value.getText());
        }
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
            setToolTipText(null);
            if (value instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof TreeEntry entry
                    && entry.classification() != ClassClassification.AUTO) {
                setIcon(new PinCompositeIcon(getIcon(), contrastingPinColor(getForeground(), getBackground())));
                setToolTipText(text(entry.classification() == ClassClassification.FORCE_CHANGED
                        ? "tree.manualClassificationChanged" : "tree.manualClassificationUnchanged"));
            }
            if (!selected && value instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof TreeEntry entry) {
                if (entry.node() == null) {
                    return component;
                }
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

        private static Color contrastingPinColor(Color foreground, Color background) {
            if (foreground != null && (background == null
                    || Math.abs(foreground.getRed() - background.getRed())
                    + Math.abs(foreground.getGreen() - background.getGreen())
                    + Math.abs(foreground.getBlue() - background.getBlue()) >= 180)) {
                return foreground;
            }
            if (background == null) return Color.BLACK;
            double luminance = background.getRed() * 0.2126
                    + background.getGreen() * 0.7152 + background.getBlue() * 0.0722;
            return luminance >= 128 ? Color.BLACK : Color.WHITE;
        }
    }

    private static final class PinCompositeIcon implements Icon {
        private static final int GAP = 3;
        private static final int PIN_WIDTH = 9;
        private final Icon base;
        private final Color color;

        PinCompositeIcon(Icon base, Color color) {
            this.base = base;
            this.color = color;
        }

        @Override public int getIconWidth() {
            return (base == null ? 0 : base.getIconWidth()) + GAP + PIN_WIDTH;
        }

        @Override public int getIconHeight() {
            return Math.max(base == null ? 0 : base.getIconHeight(), 12);
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (base != null) {
                base.paintIcon(component, graphics, x, y + (getIconHeight() - base.getIconHeight()) / 2);
            }
            int pinX = x + (base == null ? 0 : base.getIconWidth()) + GAP;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(color);
                int centerY = y + getIconHeight() / 2;
                copy.translate(pinX + 4, centerY);
                copy.rotate(Math.PI / 4.0);
                copy.fillOval(-2, -4, 4, 4);
                copy.fillRect(-3, -1, 6, 2);
                copy.drawLine(0, 1, 0, 5);
            } finally {
                copy.dispose();
            }
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
                        : match.score().total() < 0.70 ? text("candidate.status.lowConfidence")
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
                case MANUAL_CONFIRMED -> text("candidate.status.locked");
                case SUGGESTED -> text("candidate.status.suggested");
            };
        }
    }
}
