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
import java.util.Locale;
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
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.earthsworth.wmatcher.app.WorkspaceController;

public final class NewProjectDialog extends JDialog {
    private final JTextField leftPath = new JTextField();
    private final JTextField rightPath = new JTextField();
    private final JSpinner targetRelease = new JSpinner(new SpinnerNumberModel(21, 8, 99, 1));
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
        JPanel release = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        release.add(new JLabel(text("start.target")));
        release.add(targetRelease);
        options.add(showAdvanced, BorderLayout.WEST);
        options.add(release, BorderLayout.EAST);
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
        field.setTransferHandler(new JarDropHandler(field));
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
        JButton browse = new JButton(text("start.browse"));
        browse.addActionListener(event -> chooseJar(field));
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
        constraints.gridx = 1;
        panel.add(choose, constraints);
        constraints.gridx = 2;
        constraints.weightx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(count, constraints);
    }

    private void submit(Consumer<WorkspaceController.CompareRequest> compare) {
        Path left = path(leftPath);
        Path right = path(rightPath);
        if (left == null || right == null || !Files.isRegularFile(left) || !Files.isRegularFile(right)) {
            JOptionPane.showMessageDialog(this, text("dialog.invalidInput"), text("dialog.error"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        dispose();
        compare.accept(WorkspaceController.CompareRequest.fresh(
                left, right, (Integer) targetRelease.getValue(), leftLibraries, rightLibraries));
    }

    private void chooseJar(JTextField target) {
        JFileChooser chooser = jarChooser(text("dialog.chooseJar"), false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void chooseLibraries(List<Path> target, JLabel count) {
        JFileChooser chooser = jarChooser(text("dialog.chooseLibraries"), true);
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
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
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

    private static JFileChooser jarChooser(String title, boolean multiple) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setMultiSelectionEnabled(multiple);
        chooser.setFileFilter(new FileNameExtensionFilter("Java archives (*.jar)", "jar"));
        return chooser;
    }

    private static Path path(JTextField field) {
        try {
            return field.getText().isBlank() ? null : Path.of(field.getText()).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class JarDropHandler extends TransferHandler {
        private final JTextField target;

        JarDropHandler(JTextField target) {
            this.target = target;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Object value = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (value instanceof List<?> files && !files.isEmpty() && files.getFirst() instanceof File file
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    target.setText(file.toPath().toAbsolutePath().normalize().toString());
                    return true;
                }
            } catch (Exception ignored) {
                return false;
            }
            return false;
        }
    }
}
