package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
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
    private final CardLayout recentCards = new CardLayout();
    private final JPanel recentContent = new JPanel(recentCards);
    private final Consumer<Path> openRecent;
    private final Consumer<Path> removeRecent;
    private int hoveredRecentIndex = -1;

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
        recentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recentList.setCellRenderer(new RecentProjectRenderer());
        recentList.setFixedCellHeight(58);
        recentList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        MouseAdapter recentMouseHandler = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = recentIndexAt(event.getPoint());
                if (index < 0) {
                    return;
                }
                if (removeHit(index, event.getPoint())) {
                    removeAt(index);
                    return;
                }
                if (event.getClickCount() == 2) {
                    recentList.setSelectedIndex(index);
                    openSelected();
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                int index = recentIndexAt(event.getPoint());
                int nextHover = index >= 0 && removeHit(index, event.getPoint()) ? index : -1;
                if (nextHover != hoveredRecentIndex) {
                    hoveredRecentIndex = nextHover;
                    recentList.repaint();
                }
                recentList.setToolTipText(nextHover >= 0 ? text("start.removeRecentHint") : null);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hoveredRecentIndex = -1;
                recentList.setToolTipText(null);
                recentList.repaint();
            }
        };
        recentList.addMouseListener(recentMouseHandler);
        recentList.addMouseMotionListener(recentMouseHandler);
        recentList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        recentList.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openSelected(); }
        });
        recentList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        recentList.getActionMap().put("remove", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { removeSelected(); }
        });
        JLabel empty = new JLabel(text("start.noRecentProjects"), SwingConstants.CENTER);
        empty.setForeground(UIManager.getColor("Label.disabledForeground"));
        recentContent.add(empty, "empty");
        recentContent.add(new JScrollPane(recentList), "list");
        panel.add(recentContent, BorderLayout.CENTER);
        if (!projects.isEmpty()) {
            recentList.setSelectedIndex(0);
        }
        updateRecentActions();
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
            removeAt(index);
        }
    }

    private void removeAt(int index) {
        AppPreferences.RecentProject selected = recentModel.get(index);
        recentModel.remove(index);
        removeRecent.accept(selected.path());
        hoveredRecentIndex = -1;
        if (!recentModel.isEmpty()) {
            recentList.setSelectedIndex(Math.min(index, recentModel.size() - 1));
        }
        updateRecentActions();
    }

    private int recentIndexAt(Point point) {
        int index = recentList.locationToIndex(point);
        if (index < 0) {
            return -1;
        }
        Rectangle bounds = recentList.getCellBounds(index, index);
        return bounds != null && bounds.contains(point) ? index : -1;
    }

    private boolean removeHit(int index, Point point) {
        Rectangle bounds = recentList.getCellBounds(index, index);
        return bounds != null && point.x >= bounds.x + bounds.width - 36;
    }

    private void updateRecentActions() {
        boolean hasProjects = !recentModel.isEmpty();
        recentCards.show(recentContent, hasProjects ? "list" : "empty");
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

    private final class RecentProjectRenderer extends DefaultListCellRenderer {
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
            JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
            trailing.setOpaque(false);
            if (!exists) {
                JLabel missing = new JLabel(text("start.missingProject"));
                missing.setForeground(foreground);
                trailing.add(missing);
            }
            JLabel remove = new JLabel("×", SwingConstants.CENTER);
            remove.setPreferredSize(new Dimension(24, 24));
            remove.setFont(remove.getFont().deriveFont(Font.BOLD, 18f));
            Color muted = UIManager.getColor("Label.disabledForeground");
            Color accent = UIManager.getColor("Component.accentColor");
            remove.setForeground(index == hoveredRecentIndex && accent != null ? accent : muted);
            trailing.add(remove);
            cell.add(trailing, BorderLayout.EAST);
            cell.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            return cell;
        }
    }
}
