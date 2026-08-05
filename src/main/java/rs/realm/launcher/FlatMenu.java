package rs.realm.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * The account menu, in the launcher's own colours.
 *
 * A {@code JPopupMenu} is a system component: white panel, system border, system highlight. Next to
 * a dark window it is the one thing on screen that plainly came from somewhere else. This is the
 * same borderless-window trick {@link Dropdown} uses, so the two match by construction rather than
 * by two sets of colours kept in step by hand.
 *
 * <p>Separators are a row with no action, so the menu is still a flat list of items to lay out.
 */
public final class FlatMenu {

    private static final int ROW_HEIGHT = 32;
    private static final int SEPARATOR_HEIGHT = 9;
    private static final int ARC = 8;

    private record Item(String label, Runnable action, boolean destructive) {
        boolean isSeparator() {
            return label == null;
        }

        int height() {
            return isSeparator() ? SEPARATOR_HEIGHT : ROW_HEIGHT;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private JWindow popup;

    public FlatMenu add(String label, Runnable action) {
        items.add(new Item(label, action, false));
        return this;
    }

    /** An item whose consequences are hard to undo, drawn in the warning colour. */
    public FlatMenu addDestructive(String label, Runnable action) {
        items.add(new Item(label, action, true));
        return this;
    }

    public FlatMenu addSeparator() {
        items.add(new Item(null, null, false));
        return this;
    }

    /** Opens under [anchor], right-aligned to it. */
    public void show(JComponent anchor) {
        close();
        if (items.isEmpty() || !anchor.isShowing()) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        if (owner == null) {
            return;
        }

        int width = Math.max(180, widestLabel() + 40);
        int height = items.stream().mapToInt(Item::height).sum() + 8;

        MenuPanel panel = new MenuPanel(width, height);
        popup = new JWindow(owner);
        popup.setContentPane(panel);
        popup.setFocusableWindowState(false);
        popup.setBackground(Dropdown.translucencySupported() ? Dropdown.TRANSPARENT : Theme.PANEL);

        Point origin = anchor.getLocationOnScreen();
        // Right-aligned: the anchor sits at the top right of the window, so growing leftwards is the
        // only direction that keeps the menu on screen.
        int x = origin.x + anchor.getWidth() - width;
        popup.setBounds(x, origin.y + anchor.getHeight() + 6, width, height);
        popup.setVisible(true);

        owner.addComponentListener(dismiss);
        owner.addWindowFocusListener(dismissOnBlur);
    }

    public void close() {
        if (popup == null) {
            return;
        }
        Window owner = popup.getOwner();
        if (owner != null) {
            owner.removeComponentListener(dismiss);
            owner.removeWindowFocusListener(dismissOnBlur);
        }
        popup.dispose();
        popup = null;
    }

    private int widestLabel() {
        int widest = 0;
        var metrics =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        .getGraphics()
                        .getFontMetrics(new Font("SansSerif", Font.PLAIN, 13));
        for (Item item : items) {
            if (!item.isSeparator()) {
                widest = Math.max(widest, metrics.stringWidth(item.label()));
            }
        }
        return widest;
    }

    private final java.awt.event.ComponentAdapter dismiss =
            new java.awt.event.ComponentAdapter() {
                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    close();
                }

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    close();
                }
            };

    private final java.awt.event.WindowFocusListener dismissOnBlur =
            new java.awt.event.WindowAdapter() {
                @Override
                public void windowLostFocus(java.awt.event.WindowEvent e) {
                    close();
                }
            };

    private final class MenuPanel extends JComponent {
        private final int width;
        private final int height;
        private int hoverIndex = -1;

        MenuPanel(int width, int height) {
            this.width = width;
            this.height = height;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font("SansSerif", Font.PLAIN, 13));
            addMouseMotionListener(
                    new MouseMotionAdapter() {
                        @Override
                        public void mouseMoved(MouseEvent e) {
                            int index = indexAt(e.getY());
                            if (index != hoverIndex) {
                                hoverIndex = index;
                                repaint();
                            }
                        }
                    });
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mouseExited(MouseEvent e) {
                            hoverIndex = -1;
                            repaint();
                        }

                        @Override
                        public void mousePressed(MouseEvent e) {
                            int index = indexAt(e.getY());
                            if (index < 0) {
                                return;
                            }
                            Item item = items.get(index);
                            if (item.isSeparator()) {
                                return;
                            }
                            // Close BEFORE running: the action may open a dialog, and a menu still
                            // floating above it is both wrong and unreachable.
                            close();
                            item.action().run();
                        }
                    });
        }

        private int indexAt(int y) {
            int top = 4;
            for (int i = 0; i < items.size(); i++) {
                int bottom = top + items.get(i).height();
                if (y >= top && y < bottom) {
                    return i;
                }
                top = bottom;
            }
            return -1;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.PANEL);
            g2.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC);

            g2.setFont(getFont());
            var fm = g2.getFontMetrics();
            int y = 4;
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                if (item.isSeparator()) {
                    g2.setColor(Theme.BORDER);
                    g2.setStroke(new BasicStroke(1f));
                    int mid = y + SEPARATOR_HEIGHT / 2;
                    g2.drawLine(10, mid, width - 10, mid);
                } else {
                    if (i == hoverIndex) {
                        g2.setColor(Theme.BORDER);
                        g2.fillRoundRect(4, y + 1, width - 9, ROW_HEIGHT - 2, 6, 6);
                    }
                    Color text = item.destructive() ? Theme.RED.brighter() : Theme.TEXT;
                    g2.setColor(text);
                    g2.drawString(item.label(), 14, y + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent());
                }
                y += item.height();
            }
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(width, height);
        }
    }
}
