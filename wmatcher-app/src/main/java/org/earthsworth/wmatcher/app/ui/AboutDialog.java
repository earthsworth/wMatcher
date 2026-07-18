package org.earthsworth.wmatcher.app.ui;

import static org.earthsworth.wmatcher.app.I18n.text;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.event.HyperlinkEvent;
import org.earthsworth.wmatcher.app.AppMetadata;
import org.earthsworth.wmatcher.app.ShortcutId;
import org.earthsworth.wmatcher.app.ShortcutManager;

public final class AboutDialog extends JDialog {
    public AboutDialog(Window owner) {
        super(owner, text("menu.about"), Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

        JTextArea description = new JTextArea(text("dialog.about", AppMetadata.version()));
        description.setEditable(false);
        description.setFocusable(false);
        description.setOpaque(false);
        description.setBorder(null);
        content.add(description, BorderLayout.NORTH);
        content.add(link(), BorderLayout.CENTER);
        content.add(actions(), BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
                .put(ShortcutManager.stroke(ShortcutId.CLOSE_CHILD), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dispose(); }
        });
        pack();
        setLocationRelativeTo(owner);
    }

    private JEditorPane link() {
        return repositoryLink(this::openRepository);
    }

    static JEditorPane repositoryLink(Runnable openRepository) {
        JEditorPane link = new JEditorPane("text/html", "<html><a href=\"" + AppMetadata.GITHUB_URL
                + "\">" + text("dialog.aboutGitHub") + "</a><br>" + AppMetadata.GITHUB_URL + "</html>");
        link.setEditable(false);
        link.setOpaque(false);
        link.setBorder(null);
        link.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        link.setFont(UIManager.getFont("Label.font"));
        link.setForeground(UIManager.getColor("Label.foreground"));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openRepository.run();
            }
        });
        return link;
    }

    private JPanel actions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton copy = new JButton(text("dialog.copyLink"));
        copy.addActionListener(event -> copyLink(true));
        JButton close = new JButton(text("dialog.close"));
        close.addActionListener(event -> dispose());
        getRootPane().setDefaultButton(close);
        actions.add(copy);
        actions.add(close);
        return actions;
    }

    private void openRepository() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                copyLink(true);
                return;
            }
            Desktop.getDesktop().browse(URI.create(AppMetadata.GITHUB_URL));
        } catch (Exception exception) {
            copyLink(true);
        }
    }

    private void copyLink(boolean notify) {
        getToolkit().getSystemClipboard().setContents(new StringSelection(AppMetadata.GITHUB_URL), null);
        if (notify) {
            JOptionPane.showMessageDialog(this, text("dialog.linkCopied"), text("menu.about"),
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
