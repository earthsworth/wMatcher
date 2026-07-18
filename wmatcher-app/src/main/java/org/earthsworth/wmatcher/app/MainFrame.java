package org.earthsworth.wmatcher.app;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileFilter;
import org.earthsworth.wmatcher.app.ui.AboutDialog;
import org.earthsworth.wmatcher.app.ui.NewProjectDialog;
import org.earthsworth.wmatcher.app.ui.StartPanel;
import org.earthsworth.wmatcher.app.ui.WorkspacePanel;
import org.earthsworth.wmatcher.app.ui.NamespaceDialog;
import org.earthsworth.wmatcher.app.ui.ShortcutSettingsDialog;
import org.earthsworth.wmatcher.app.ui.GlobalSearchDialog;
import org.earthsworth.wmatcher.core.model.MappingFileFormat;
import org.earthsworth.wmatcher.core.model.MappingNamespaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MainFrame extends JFrame {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainFrame.class);
    private static final String START = "start";
    private static final String WORKSPACE = "workspace";
    private final WorkspaceController controller;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel status = new JLabel(text("status.ready"));
    private final JProgressBar progress = new JProgressBar();
    private final JButton cancel = new JButton(text("status.cancel"));
    private final UnsavedChangesGuard unsavedChangesGuard = new UnsavedChangesGuard();
    private StartPanel startPanel;
    private WorkspacePanel workspacePanel;
    private final Map<ShortcutId, javax.swing.KeyStroke> installedShortcuts = new EnumMap<>(ShortcutId.class);

    public MainFrame(WorkspaceController controller) {
        super(text("app.title"));
        this.controller = controller;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(800, 500));
        setSize(800, 500);
        setLocationRelativeTo(null);
        setJMenuBar(menuBar());
        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestShutdown();
            }
        });
        controller.addDocumentStateListener(ignored -> updateWindowTitle());
        showWelcome();
        installGlobalShortcuts();
        updateWindowTitle();
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu(text("menu.file"));
        file.add(item(text("menu.new"), this::requestNewProject, ShortcutId.NEW_PROJECT));
        file.add(item(text("menu.open"), this::requestOpenProject, ShortcutId.OPEN_PROJECT));
        file.addSeparator();
        file.add(item(text("menu.save"), () -> saveProject(false), ShortcutId.SAVE_PROJECT));
        file.add(item(text("menu.saveAs"), () -> saveProject(true), ShortcutId.SAVE_AS));
        file.addSeparator();
        JMenu imports = new JMenu(text("menu.importMappings"));
        for (MappingFileFormat format : MappingFileFormat.values()) {
            imports.add(item(mappingLabel(format), () -> importMapping(format)));
        }
        JMenu exports = new JMenu(text("menu.exportMappings"));
        for (MappingFileFormat format : MappingFileFormat.values()) {
            exports.add(item(mappingLabel(format), () -> exportMapping(format)));
        }
        file.add(imports);
        file.add(exports);
        file.addSeparator();
        file.add(item(text("menu.exit"), this::requestShutdown));
        bar.add(file);

        JMenu edit = new JMenu(text("menu.edit"));
        edit.add(item(text("menu.undo"), () -> {
            if (workspacePanel != null) workspacePanel.undo();
        }, ShortcutId.UNDO));
        edit.add(item(text("menu.redo"), () -> {
            if (workspacePanel != null) workspacePanel.redo();
        }, ShortcutId.REDO));
        edit.add(item(text("menu.globalSearch"), this::showGlobalSearch, ShortcutId.GLOBAL_SEARCH));
        edit.addSeparator();
        edit.add(item(text("menu.shortcuts"), () -> new ShortcutSettingsDialog(this, () -> {
            setJMenuBar(menuBar());
            installGlobalShortcuts();
            if (workspacePanel != null) workspacePanel.refreshShortcuts();
            if (startPanel != null) startPanel.refreshShortcuts();
        }).setVisible(true)));
        bar.add(edit);

        JMenu view = new JMenu(text("menu.view"));
        view.add(item(text("menu.themeSystem"), () -> WMatcherApplication.installTheme("system")));
        view.add(item(text("menu.themeLight"), () -> WMatcherApplication.installTheme("light")));
        view.add(item(text("menu.themeDark"), () -> WMatcherApplication.installTheme("dark")));
        view.addSeparator();
        JCheckBoxMenuItem showMinimap = new JCheckBoxMenuItem(text("menu.showMinimap"),
                AppPreferences.minimapVisible());
        showMinimap.addActionListener(event -> {
            boolean visible = showMinimap.isSelected();
            AppPreferences.setMinimapVisible(visible);
            if (workspacePanel != null) {
                workspacePanel.setMinimapsVisible(visible);
            }
        });
        view.add(showMinimap);
        JMenu language = new JMenu(text("menu.language"));
        language.add(item(text("menu.english"), () -> setLanguage(Locale.ENGLISH)));
        language.add(item(text("menu.chinese"), () -> setLanguage(Locale.SIMPLIFIED_CHINESE)));
        view.add(language);
        bar.add(view);

        JMenu help = new JMenu(text("menu.help"));
        help.add(item(text("menu.about"), () -> new AboutDialog(this).setVisible(true)));
        bar.add(help);
        return bar;
    }

    private JPanel statusBar() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        progress.setVisible(false);
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(260, 20));
        cancel.setVisible(false);
        cancel.addActionListener(event -> {
            controller.cancel();
            setBusy(false);
        });
        panel.add(status, BorderLayout.CENTER);
        JPanel right = new JPanel(new BorderLayout(6, 0));
        right.add(progress, BorderLayout.CENTER);
        right.add(cancel, BorderLayout.EAST);
        panel.add(right, BorderLayout.EAST);
        return panel;
    }

    private void showWelcome() {
        controller.cancel();
        installWelcomePanel();
    }

    private void installWelcomePanel() {
        if (startPanel != null) {
            content.remove(startPanel);
        }
        startPanel = new StartPanel(
                this::requestNewProject,
                this::requestOpenProject,
                path -> runAfterUnsavedCheck(() -> openProject(path)),
                AppPreferences::removeRecentProject,
                AppPreferences.recentProjects());
        content.add(startPanel, START);
        cards.show(content, START);
        setBusy(false);
        status.setText(text("status.ready"));
    }

    private void requestNewProject() {
        runAfterUnsavedCheck(this::showNewProjectDialog);
    }

    private void showNewProjectDialog() {
        new NewProjectDialog(this, this::compare).setVisible(true);
    }

    private void compare(WorkspaceController.CompareRequest request) {
        setBusy(true);
        controller.compare(request, this::updateProgress, this::showWorkspace, this::showError);
    }

    private void showWorkspace(WorkspaceController.Workspace workspace) {
        setBusy(false);
        if (workspacePanel != null) {
            content.remove(workspacePanel);
        }
        workspacePanel = new WorkspacePanel(controller, workspace, this::showError);
        workspacePanel.setMinimapsVisible(AppPreferences.minimapVisible());
        content.add(workspacePanel, WORKSPACE);
        cards.show(content, WORKSPACE);
        status.setText(workspace.warning().isBlank() ? text("status.ready") : workspace.warning());
        if (workspace.projectPath() != null) {
            AppPreferences.setRecentProject(workspace.projectPath());
        }
        if (!workspace.warning().isBlank()) {
            JOptionPane.showMessageDialog(this, workspace.warning(), text("dialog.error"),
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void updateProgress(WorkspaceController.ProgressUpdate update) {
        status.setText(update.stage());
        if (update.total() > 0 && update.total() <= Integer.MAX_VALUE) {
            progress.setIndeterminate(false);
            progress.setMaximum((int) update.total());
            progress.setValue((int) Math.min(update.completed(), update.total()));
            progress.setString(update.stage());
        } else {
            progress.setIndeterminate(true);
            progress.setString(update.stage());
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        progress.setIndeterminate(busy);
        progress.setString(busy ? text("status.loading") : "");
        cancel.setVisible(busy);
    }

    private void chooseAndOpenProject() {
        JFileChooser chooser = chooser(text("dialog.openProject"), "wMatcher projects (*.wmatch)", "wmatch");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openProject(chooser.getSelectedFile().toPath());
        }
    }

    private void requestOpenProject() {
        runAfterUnsavedCheck(this::chooseAndOpenProject);
    }

    private void openProject(Path path) {
        setBusy(true);
        controller.openProject(path, this::updateProgress, this::showWorkspace, this::showError);
    }

    private void saveProject(boolean choosePath) {
        saveProject(choosePath, () -> { });
    }

    private void saveProject(boolean choosePath, Runnable afterSave) {
        WorkspaceController.Workspace workspace = controller.workspace();
        if (workspace == null || workspacePanel == null) {
            return;
        }
        Path destination = choosePath ? null : workspace.projectPath();
        if (destination == null) {
            JFileChooser chooser = chooser(text("dialog.saveProject"), "wMatcher projects (*.wmatch)", "wmatch");
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            destination = withExtension(chooser.getSelectedFile().toPath(), ".wmatch");
        }
        Path finalDestination = destination;
        setSaving();
        controller.saveProject(destination, workspacePanel.uiState(), () -> {
            setBusy(false);
            AppPreferences.setRecentProject(finalDestination);
            status.setText(text("message.saved"));
            afterSave.run();
        }, this::showError);
    }

    private void importMapping(MappingFileFormat format) {
        if (controller.workspace() == null) return;
        JFileChooser chooser = mappingChooser(text("dialog.importMapping"), format, true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        controller.mappingNamespaces(path, format, namespaces -> {
            MappingNamespaces selected = selectNamespaces(format, namespaces);
            if (namespaces.size() > 2 && selected == null) return;
            controller.importMappingFile(format, path, selected, (workspace, result) -> {
                workspacePanel.refresh(workspace);
                status.setText(text("message.importedMappings", result.imported(), result.skipped()));
            }, this::showError);
        }, this::showError);
    }

    private void exportMapping(MappingFileFormat format) {
        if (controller.workspace() == null) return;
        JFileChooser chooser = mappingChooser(text("dialog.exportMapping"), format, false);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (format == MappingFileFormat.ENIGMA && java.nio.file.Files.isDirectory(path)
                && JOptionPane.showConfirmDialog(this, text("dialog.replaceMappingDirectory"),
                text("dialog.exportMapping"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (format != MappingFileFormat.ENIGMA || !java.nio.file.Files.isDirectory(path)) {
            path = withExtension(path, mappingExtension(format));
        }
        Path destination = path;
        controller.exportMappings(destination, format,
                () -> status.setText(text("message.exportedMappings", mappingLabel(format))), this::showError);
    }

    private MappingNamespaces selectNamespaces(MappingFileFormat format, java.util.List<String> namespaces) {
        if (format != MappingFileFormat.TINY_V1 && format != MappingFileFormat.TINY_V2) return null;
        if (namespaces.size() < 2) return null;
        if (namespaces.size() == 2) return new MappingNamespaces(namespaces.getFirst(), namespaces.get(1));
        return NamespaceDialog.showDialog(this, namespaces);
    }

    private static String mappingLabel(MappingFileFormat format) {
        return text("mapping.format." + format.name().toLowerCase(Locale.ROOT));
    }

    private static String mappingExtension(MappingFileFormat format) {
        return switch (format) {
            case ENIGMA -> ".mapping";
            case JADX_LEGACY -> ".jobf";
            case PROGUARD -> ".txt";
            case SRG -> ".srg";
            case SIMPLE -> ".txt";
            case TINY_V1, TINY_V2 -> ".tiny";
        };
    }

    private void setLanguage(Locale locale) {
        try {
            var uiState = workspacePanel == null ? null : workspacePanel.uiState();
            I18n.use(locale);
            setJMenuBar(menuBar());
            cancel.setText(text("status.cancel"));
            WorkspaceController.Workspace current = controller.workspace();
            if (current == null) {
                installWelcomePanel();
            } else {
                if (workspacePanel != null) content.remove(workspacePanel);
                WorkspaceController.Workspace localized = uiState == null ? current : current.withUiState(uiState);
                workspacePanel = new WorkspacePanel(controller, localized, this::showError);
                workspacePanel.setMinimapsVisible(AppPreferences.minimapVisible());
                content.add(workspacePanel, WORKSPACE);
                cards.show(content, WORKSPACE);
                status.setText(current.warning().isBlank() ? text("status.ready") : current.warning());
            }
            updateWindowTitle();
            revalidate();
            repaint();
        } catch (RuntimeException exception) {
            showError(exception);
        }
    }

    private void showError(Throwable throwable) {
        setBusy(false);
        LOGGER.error("wMatcher operation failed", throwable);
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        JOptionPane.showMessageDialog(this, message, text("dialog.error"), JOptionPane.ERROR_MESSAGE);
        status.setText(text("status.ready"));
    }

    private void runAfterUnsavedCheck(Runnable continuation) {
        unsavedChangesGuard.proceed(
                controller.hasUnsavedChanges(),
                this::promptUnsavedChanges,
                success -> saveProject(false, success),
                continuation);
    }

    private UnsavedChangesGuard.Decision promptUnsavedChanges() {
        Object[] options = {text("dialog.save"), text("dialog.dontSave"), text("dialog.cancel")};
        int result = JOptionPane.showOptionDialog(
                this,
                text("dialog.unsaved"),
                text("dialog.unsavedTitle"),
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        return switch (result) {
            case 0 -> UnsavedChangesGuard.Decision.SAVE;
            case 1 -> UnsavedChangesGuard.Decision.DISCARD;
            default -> UnsavedChangesGuard.Decision.CANCEL;
        };
    }

    private void requestShutdown() {
        runAfterUnsavedCheck(this::shutdownImmediately);
    }

    private void shutdownImmediately() {
        controller.close();
        dispose();
    }

    private void updateWindowTitle() {
        setTitle(text("app.title") + (controller.hasUnsavedChanges() ? " *" : ""));
    }

    private void installGlobalShortcuts() {
        javax.swing.InputMap input = getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap actions = getRootPane().getActionMap();
        installedShortcuts.forEach((id, stroke) -> input.remove(stroke));
        installedShortcuts.clear();
        bindGlobal(input, actions, ShortcutId.GLOBAL_SEARCH, this::showGlobalSearch);
        bindGlobal(input, actions, ShortcutId.FOCUS_TREE,
                () -> { if (workspacePanel != null) workspacePanel.focusTree(); });
        bindGlobal(input, actions, ShortcutId.TAB_OVERVIEW,
                () -> { if (workspacePanel != null) workspacePanel.selectDetailTab(0); });
        bindGlobal(input, actions, ShortcutId.TAB_CANDIDATES,
                () -> { if (workspacePanel != null) workspacePanel.selectDetailTab(1); });
        bindGlobal(input, actions, ShortcutId.TAB_STRUCTURE,
                () -> { if (workspacePanel != null) workspacePanel.selectDetailTab(2); });
        bindGlobal(input, actions, ShortcutId.TAB_BYTECODE,
                () -> { if (workspacePanel != null) workspacePanel.selectDetailTab(3); });
        bindGlobal(input, actions, ShortcutId.TAB_SOURCE,
                () -> { if (workspacePanel != null) workspacePanel.selectDetailTab(4); });
    }

    private void showGlobalSearch() {
        if (controller.workspace() == null || workspacePanel == null) return;
        new GlobalSearchDialog(this, controller, workspacePanel::navigateToSearchHit,
                workspacePanel::canonicalNamesDirection).setVisible(true);
    }

    private void bindGlobal(
            javax.swing.InputMap input,
            javax.swing.ActionMap actions,
            ShortcutId id,
            Runnable action) {
        javax.swing.KeyStroke stroke = ShortcutManager.stroke(id);
        String actionKey = "shortcut." + id.name();
        input.put(stroke, actionKey);
        actions.put(actionKey, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
        installedShortcuts.put(id, stroke);
    }

    private void setSaving() {
        progress.setVisible(true);
        progress.setIndeterminate(true);
        progress.setString(text("menu.save"));
        cancel.setVisible(false);
    }

    private static JMenuItem item(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> action.run());
        return item;
    }

    private static JMenuItem item(String label, Runnable action, ShortcutId shortcut) {
        JMenuItem item = item(label, action);
        item.setAccelerator(ShortcutManager.stroke(shortcut));
        return item;
    }

    private static JFileChooser chooser(String title, String description, String... extensions) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(description, extensions));
        return chooser;
    }

    private static JFileChooser mappingChooser(String title, MappingFileFormat format, boolean open) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        if (format == MappingFileFormat.ENIGMA) {
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        } else {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        }
        String extension = mappingExtension(format);
        chooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(java.io.File file) {
                if (file.isDirectory()) return format == MappingFileFormat.ENIGMA;
                return file.getName().toLowerCase(Locale.ROOT).endsWith(extension);
            }

            @Override
            public String getDescription() {
                return text("mapping.filter", mappingLabel(format));
            }
        });
        return chooser;
    }

    private static Path withExtension(Path path, String extension) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)
                ? path : path.resolveSibling(path.getFileName() + extension);
    }
}
