package org.earthsworth.wmatcher.app;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import org.earthsworth.wmatcher.app.ui.AboutDialog;
import org.earthsworth.wmatcher.app.ui.NewProjectDialog;
import org.earthsworth.wmatcher.app.ui.StartPanel;
import org.earthsworth.wmatcher.app.ui.WorkspacePanel;
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

    public MainFrame(WorkspaceController controller) {
        super(text("app.title"));
        this.controller = controller;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 680));
        setSize(1440, 900);
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
        updateWindowTitle();
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu(text("menu.file"));
        file.add(item(text("menu.new"), this::requestNewProject));
        file.add(item(text("menu.open"), this::requestOpenProject));
        file.addSeparator();
        file.add(item(text("menu.save"), () -> saveProject(false)));
        file.add(item(text("menu.saveAs"), () -> saveProject(true)));
        file.addSeparator();
        file.add(item(text("menu.importTiny"), this::importTiny));
        file.add(item(text("menu.importProguard"), this::importProguard));
        file.add(item(text("menu.exportTiny"), this::exportTiny));
        file.addSeparator();
        file.add(item(text("menu.exit"), this::requestShutdown));
        bar.add(file);

        JMenu edit = new JMenu(text("menu.edit"));
        edit.add(item(text("menu.undo"), () -> {
            if (workspacePanel != null) workspacePanel.undo();
        }));
        edit.add(item(text("menu.redo"), () -> {
            if (workspacePanel != null) workspacePanel.redo();
        }));
        bar.add(edit);

        JMenu view = new JMenu(text("menu.view"));
        view.add(item(text("menu.themeSystem"), () -> WMatcherApplication.installTheme("system")));
        view.add(item(text("menu.themeLight"), () -> WMatcherApplication.installTheme("light")));
        view.add(item(text("menu.themeDark"), () -> WMatcherApplication.installTheme("dark")));
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

    private void importTiny() {
        if (controller.workspace() == null) return;
        JFileChooser chooser = chooser(text("dialog.importTiny"), "Tiny v2 mappings (*.tiny)", "tiny");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.importTiny(chooser.getSelectedFile().toPath(), workspace -> {
                workspacePanel.refresh(workspace);
                status.setText(text("message.imported"));
            }, this::showError);
        }
    }

    private void importProguard() {
        if (controller.workspace() == null) return;
        JFileChooser leftChooser = chooser(text("dialog.leftProguard"), "ProGuard/R8 mappings (*.txt, *.map)", "txt", "map");
        if (leftChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        JFileChooser rightChooser = chooser(text("dialog.rightProguard"), "ProGuard/R8 mappings (*.txt, *.map)", "txt", "map");
        if (rightChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        controller.importProguard(leftChooser.getSelectedFile().toPath(), rightChooser.getSelectedFile().toPath(),
                workspace -> {
                    workspacePanel.refresh(workspace);
                    status.setText(text("message.imported"));
                }, this::showError);
    }

    private void exportTiny() {
        if (controller.workspace() == null) return;
        JFileChooser chooser = chooser(text("dialog.exportTiny"), "Tiny v2 mappings (*.tiny)", "tiny");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path path = withExtension(chooser.getSelectedFile().toPath(), ".tiny");
            controller.exportTiny(path, () -> status.setText(text("message.exported")), this::showError);
        }
    }

    private void setLanguage(Locale locale) {
        I18n.use(locale);
        updateWindowTitle();
        JOptionPane.showMessageDialog(this, text("dialog.languageRestart"), text("menu.language"),
                JOptionPane.INFORMATION_MESSAGE);
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

    private static JFileChooser chooser(String title, String description, String... extensions) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(description, extensions));
        return chooser;
    }

    private static Path withExtension(Path path, String extension) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)
                ? path : path.resolveSibling(path.getFileName() + extension);
    }
}
