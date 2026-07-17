package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import org.earthsworth.wmatcher.app.AppPreferences;

public final class StartPanel extends JPanel {
    private final DefaultListModel<AppPreferences.RecentProject> recentModel = new DefaultListModel<>();
    private final JList<AppPreferences.RecentProject> recentList = new JList<>(recentModel);
    private final Consumer<Path> openRecent;
    private final Consumer<Path> removeRecent;

    public StartPanel(
            Runnable newProject,
            Runnable openProject,
            Consumer<Path> openRecent,
            Consumer<Path> removeRecent,
            List<AppPreferences.RecentProject> recentProjects) {
        super(new BorderLayout());
        this.openRecent = openRecent;
        this.removeRecent = removeRecent;
        setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
        add(actionRail(newProject, openProject), BorderLayout.WEST);
        add(recentProjects(recentProjects), BorderLayout.CENTER);
    }

    JList<AppPreferences.RecentProject> recentListForTesting() {
        return recentList;
    }

    private JPanel actionRail(Runnable newProject, Runnable openProject) {
        JPanel rail = new JPanel();
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        rail.setBorder(BorderFactory.createEmptyBorder(18, 12, 18, 34));
        rail.setPreferredSize(new Dimension(250, 0));
        JLabel title = new JLabel("wMatcher");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel(text("start.welcomeSubtitle"));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton create = actionButton(text("start.newProject"), newProject, true);
        JButton open = actionButton(text("start.openProject"), openProject, false);
        rail.add(title);
        rail.add(Box.createVerticalStrut(4));
        rail.add(subtitle);
        rail.add(Box.createVerticalStrut(32));
        rail.add(create);
        rail.add(Box.createVerticalStrut(10));
        rail.add(open);
        rail.add(Box.createVerticalGlue());
        return rail;
    }

    private JPanel recentProjects(List<AppPreferences.RecentProject> projects) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 12));
        JLabel heading = new JLabel(text("start.recentProjects"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        panel.add(heading, BorderLayout.NORTH);
        projects.forEach(recentModel::addElement);
        if (projects.isEmpty()) {
            JLabel empty = new JLabel(text("start.noRecentProjects"), SwingConstants.CENTER);
            empty.setForeground(UIManager.getColor("Label.disabledForeground"));
            panel.add(empty, BorderLayout.CENTER);
            return panel;
        }
        recentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recentList.setCellRenderer(new RecentProjectRenderer());
        recentList.setFixedCellHeight(58);
        recentList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        recentList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        recentList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        recentList.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openSelected(); }
        });
        recentList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        recentList.getActionMap().put("remove", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { removeSelected(); }
        });
        recentList.setSelectedIndex(0);
        panel.add(new JScrollPane(recentList), BorderLayout.CENTER);
        return panel;
    }

    private void openSelected() {
        AppPreferences.RecentProject selected = recentList.getSelectedValue();
        if (selected != null && Files.isRegularFile(selected.path())) {
            openRecent.accept(selected.path());
        }
    }

    private void removeSelected() {
        int index = recentList.getSelectedIndex();
        if (index >= 0) {
            AppPreferences.RecentProject selected = recentModel.get(index);
            recentModel.remove(index);
            removeRecent.accept(selected.path());
            if (!recentModel.isEmpty()) {
                recentList.setSelectedIndex(Math.min(index, recentModel.size() - 1));
            }
        }
    }

    private static JButton actionButton(String label, Runnable action, boolean primary) {
        JButton button = new JButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        if (primary) {
            button.putClientProperty("JButton.buttonType", "roundRect");
        }
        button.addActionListener(event -> action.run());
        return button;
    }

    private static final class RecentProjectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused) {
            AppPreferences.RecentProject project = (AppPreferences.RecentProject) value;
            JPanel cell = new JPanel(new BorderLayout(8, 2));
            cell.setOpaque(true);
            cell.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            boolean exists = Files.isRegularFile(project.path());
            JLabel name = new JLabel(project.path().getFileName().toString());
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            JLabel path = new JLabel(project.path().toString());
            Color foreground = exists
                    ? selected ? list.getSelectionForeground() : list.getForeground()
                    : UIManager.getColor("Label.disabledForeground");
            name.setForeground(foreground);
            path.setForeground(foreground);
            cell.add(name, BorderLayout.NORTH);
            cell.add(path, BorderLayout.CENTER);
            if (!exists) {
                JLabel missing = new JLabel(text("start.missingProject"));
                missing.setForeground(foreground);
                cell.add(missing, BorderLayout.EAST);
            }
            cell.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            return cell;
        }
    }
}
