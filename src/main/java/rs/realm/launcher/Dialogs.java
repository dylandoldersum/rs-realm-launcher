package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * The launcher's own message, confirm and input boxes.
 *
 * {@code JOptionPane} is quick and looks like the operating system rather than the launcher: white
 * panel, system icons, system buttons. In a dark window that is the one thing on screen that did not
 * come from us, and it shows.
 *
 * <p>These are plain {@link JDialog}s with the same palette as everything else, and they behave the
 * way people expect a dialog to: Escape cancels, Enter confirms.
 */
public final class Dialogs {

    private Dialogs() {}

    /** A message with a single dismiss button. */
    public static void message(Component parent, String title, String body) {
        Dialog dialog = new Dialog(parent, title, body);
        dialog.addButton("OK", true, () -> {});
        dialog.show();
    }

    /**
     * A yes/no question, worded as the action rather than "OK".
     *
     * The confirming button carries the verb — "Remove" rather than "Yes" — so a click that cannot
     * be undone still says what it does at the moment of clicking.
     */
    public static boolean confirm(
            Component parent, String title, String body, String confirmLabel, boolean destructive) {
        Dialog dialog = new Dialog(parent, title, body);
        boolean[] answer = {false};
        dialog.addButton("Cancel", false, () -> {});
        dialog.addButton(confirmLabel, true, () -> answer[0] = true, destructive);
        dialog.show();
        return answer[0];
    }

    /** Asks for one line of text. Returns null if cancelled or left empty. */
    public static String input(Component parent, String title, String prompt) {
        Dialog dialog = new Dialog(parent, title, prompt);
        JTextField field = dialog.addField();
        String[] answer = {null};
        dialog.addButton("Cancel", false, () -> {});
        dialog.addButton(
                "Create",
                true,
                () -> {
                    String text = field.getText().trim();
                    answer[0] = text.isEmpty() ? null : text;
                });
        dialog.show();
        return answer[0];
    }

    /** The shared shell: dark panel, title, body, and a row of buttons at the bottom right. */
    private static final class Dialog {
        private final JDialog dialog;
        private final JPanel content;
        private final JPanel buttons;
        private JTextField field;

        Dialog(Component parent, String title, String body) {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            dialog = new JDialog(owner, title, JDialog.DEFAULT_MODALITY_TYPE);
            dialog.setUndecorated(true);
            dialog.setResizable(false);

            content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(Theme.PANEL);
            content.setBorder(
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Theme.BORDER, 1),
                            BorderFactory.createEmptyBorder(20, 22, 16, 22)));

            JLabel heading = new JLabel(title);
            heading.setForeground(Theme.TEXT);
            heading.setFont(new Font("SansSerif", Font.BOLD, 15));
            heading.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(heading);
            content.add(Box.createVerticalStrut(10));

            // A fixed width so the wrapping is ours rather than the longest line's. This label is
            // the one place html IS right: nothing here is inside a layout that its preferred width
            // could push around.
            JLabel text =
                    new JLabel("<html><body style='width:340px'>" + escape(body) + "</body></html>");
            text.setForeground(Theme.SUBTEXT);
            text.setFont(Theme.BODY);
            text.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(text);

            buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Theme.PANEL);
            root.add(content, BorderLayout.CENTER);
            dialog.setContentPane(root);
        }

        JTextField addField() {
            content.add(Box.createVerticalStrut(14));
            field = new JTextField();
            field.setBackground(Theme.BACKGROUND);
            field.setForeground(Theme.TEXT);
            field.setCaretColor(Theme.TEXT);
            field.setFont(new Font("SansSerif", Font.PLAIN, 14));
            field.setBorder(
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Theme.BORDER, 1),
                            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            content.add(field);
            return field;
        }

        void addButton(String label, boolean isDefault, Runnable action) {
            addButton(label, isDefault, action, false);
        }

        void addButton(String label, boolean isDefault, Runnable action, boolean destructive) {
            FlatButton button =
                    new FlatButton(
                            label,
                            isDefault,
                            destructive,
                            () -> {
                                action.run();
                                dialog.dispose();
                            });
            buttons.add(button);
            if (isDefault) {
                dialog.getRootPane()
                        .registerKeyboardAction(
                                e -> {
                                    action.run();
                                    dialog.dispose();
                                },
                                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                                JComponent.WHEN_IN_FOCUSED_WINDOW);
            }
        }

        void show() {
            content.add(Box.createVerticalStrut(18));
            content.add(buttons);
            dialog.getRootPane()
                    .registerKeyboardAction(
                            e -> dialog.dispose(),
                            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                            JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.pack();
            dialog.setLocationRelativeTo(dialog.getOwner());
            if (field != null) {
                field.requestFocusInWindow();
            }
            dialog.setVisible(true);
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>");
        }
    }

    /** A flat rounded button; filled when it is the default action, outlined otherwise. */
    private static final class FlatButton extends JComponent {
        private final String label;
        private final boolean filled;
        private final boolean destructive;
        private final Runnable action;
        private boolean hovering;

        FlatButton(String label, boolean filled, boolean destructive, Runnable action) {
            this.label = label;
            this.filled = filled;
            this.destructive = destructive;
            this.action = action;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font("SansSerif", Font.BOLD, 13));
            setPreferredSize(new Dimension(Math.max(96, label.length() * 10 + 30), 36));
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mouseEntered(MouseEvent e) {
                            hovering = true;
                            repaint();
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            hovering = false;
                            repaint();
                        }

                        @Override
                        public void mousePressed(MouseEvent e) {
                            action.run();
                        }
                    });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color accent = destructive ? Theme.RED : Theme.GREEN;
            Color accentHover = destructive ? Theme.RED.brighter() : Theme.GREEN_HOVER;

            if (filled) {
                g2.setColor(hovering ? accentHover : accent);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
                g2.setColor(Color.WHITE);
            } else {
                g2.setColor(Theme.BACKGROUND);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
                g2.setColor(hovering ? Theme.SUBTEXT : Theme.BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
                g2.setColor(Theme.TEXT);
            }
            g2.setFont(getFont());
            var fm = g2.getFontMetrics();
            g2.drawString(
                    label, (w - fm.stringWidth(label)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}
