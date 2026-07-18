package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileFilter;
import org.earthsworth.wmatcher.app.WorkspaceController;
import org.earthsworth.wmatcher.app.ShortcutId;
import org.earthsworth.wmatcher.app.ShortcutManager;

public final class NewProjectDialog extends JDialog {
    private final JTextField leftPath = new JTextField();
    private final JTextField rightPath = new JTextField();
    private final List<Path> leftLibraries = new ArrayList<>();
    private final List<Path> rightLibraries = new ArrayList<>();
    private final JLabel leftLibraryCount = new JLabel(text("start.libraryCount", 0));
    private final JLabel rightLibraryCount = new JLabel(text("start.libraryCount", 0));
    private final JPanel advanced = new JPanel(new GridBagLayout());

    public NewProjectDialog(Window owner, Consumer<WorkspaceController.CompareRequest> compare) {
        super(owner, text("dialog.newProject"), Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 12));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));
        add(form(), BorderLayout.CENTER);
        add(actions(compare), BorderLayout.SOUTH);
        installEscapeAction();
        pack();
        setMinimumSize(new Dimension(720, getHeight()));
        setLocationRelativeTo(owner);
    }

    private JPanel form() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        JPanel jars = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = constraints();
        addJarRow(jars, constraints, text("start.oldJar"), leftPath);
        addJarRow(jars, constraints, text("start.newJar"), rightPath);
        panel.add(jars, BorderLayout.NORTH);

        GridBagConstraints advancedConstraints = constraints();
        addLibraryRow(advanced, advancedConstraints, text("start.oldLibraries"), leftLibraries, leftLibraryCount);
        addLibraryRow(advanced, advancedConstraints, text("start.newLibraries"), rightLibraries, rightLibraryCount);
        advanced.setVisible(false);
        panel.add(advanced, BorderLayout.CENTER);

        JPanel options = new JPanel(new BorderLayout());
        JToggleButton showAdvanced = new JToggleButton(text("start.advanced"));
        showAdvanced.addActionListener(event -> {
            advanced.setVisible(showAdvanced.isSelected());
            showAdvanced.setText(text(showAdvanced.isSelected() ? "start.hideAdvanced" : "start.advanced"));
            pack();
        });
        options.add(showAdvanced, BorderLayout.WEST);
        panel.add(options, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel actions(Consumer<WorkspaceController.CompareRequest> compare) {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton(text("dialog.cancel"));
        cancel.addActionListener(event -> dispose());
        JButton start = new JButton(text("start.compare"));
        start.putClientProperty("JButton.buttonType", "roundRect");
        start.addActionListener(event -> submit(compare));
        getRootPane().setDefaultButton(start);
        actions.add(cancel);
        actions.add(start);
        return actions;
    }

    private void addJarRow(JPanel panel, GridBagConstraints constraints, String label, JTextField field) {
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), constraints);
        field.setTransferHandler(new PathDropHandler(paths -> field.setText(paths.getFirst().toString()), false));
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
        JButton browse = new JButton(text("start.browse"));
        browse.addActionListener(event -> chooseArtifact(field));
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(browse, constraints);
    }

    private void addLibraryRow(
            JPanel panel,
            GridBagConstraints constraints,
            String label,
            List<Path> libraries,
            JLabel count) {
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), constraints);
        JButton choose = new JButton(text("start.libraries"));
        choose.addActionListener(event -> chooseLibraries(libraries, count));
        PathDropHandler dropHandler = new PathDropHandler(paths -> {
            libraries.clear();
            libraries.addAll(paths);
            count.setText(text("start.libraryCount", libraries.size()));
        }, true);
        choose.setTransferHandler(dropHandler);
        count.setTransferHandler(dropHandler);
        constraints.gridx = 1;
        panel.add(choose, constraints);
        constraints.gridx = 2;
        constraints.weightx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(count, constraints);
        JButton clear = new JButton("\u00d7");
        clear.setToolTipText(text("start.clearLibraries"));
        clear.addActionListener(event -> {
            libraries.clear();
            count.setText(text("start.libraryCount", 0));
        });
        constraints.gridx = 3;
        constraints.weightx = 0;
        panel.add(clear, constraints);
    }

    private void submit(Consumer<WorkspaceController.CompareRequest> compare) {
        Path left = path(leftPath);
        Path right = path(rightPath);
        if (!validArtifact(left) || !validArtifact(right)) {
            JOptionPane.showMessageDialog(this, text("dialog.invalidInput"), text("dialog.error"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        dispose();
        compare.accept(WorkspaceController.CompareRequest.fresh(left, right, leftLibraries, rightLibraries));
    }

    private void chooseArtifact(JTextField target) {
        JFileChooser chooser = artifactChooser(text("dialog.chooseArtifact"), false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void chooseLibraries(List<Path> target, JLabel count) {
        JFileChooser chooser = artifactChooser(text("dialog.chooseLibraries"), true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.clear();
            for (File file : chooser.getSelectedFiles()) {
                target.add(file.toPath().toAbsolutePath().normalize());
            }
            count.setText(text("start.libraryCount", target.size()));
        }
    }

    private void installEscapeAction() {
        JRootPane root = getRootPane();
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW).put(ShortcutManager.stroke(ShortcutId.CLOSE_CHILD), "close");
        root.getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dispose(); }
        });
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = -1;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(5, 5, 5, 5);
        return constraints;
    }

    private static JFileChooser artifactChooser(String title, boolean multiple) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setMultiSelectionEnabled(multiple);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) return true;
                String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                return name.endsWith(".jar") || name.endsWith(".zip");
            }

            @Override
            public String getDescription() {
                return text("dialog.artifactFilter");
            }
        });
        return chooser;
    }

    private static Path path(JTextField field) {
        try {
            String value = normalizedPathText(field.getText());
            return value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean validArtifact(Path path) {
        return path != null && (Files.isRegularFile(path) || Files.isDirectory(path));
    }

    private static String normalizedPathText(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static final class PathDropHandler extends TransferHandler {
        private final Consumer<List<Path>> consumer;
        private final boolean multiple;

        PathDropHandler(Consumer<List<Path>> consumer, boolean multiple) {
            this.consumer = consumer;
            this.multiple = multiple;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                List<Path> paths = new ArrayList<>();
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    Object value = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (value instanceof List<?> files) {
                        for (Object candidate : files) {
                            if (candidate instanceof File file) {
                                paths.add(file.toPath().toAbsolutePath().normalize());
                                if (!multiple) break;
                            }
                        }
                    }
                } else {
                    String value = String.valueOf(support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    for (String line : value.split("\\R")) {
                        String normalized = normalizedPathText(line);
                        if (!normalized.isBlank()) {
                            paths.add(Path.of(normalized).toAbsolutePath().normalize());
                            if (!multiple) break;
                        }
                    }
                }
                paths.removeIf(path -> !validArtifact(path));
                if (paths.isEmpty()) return false;
                consumer.accept(List.copyOf(paths));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
