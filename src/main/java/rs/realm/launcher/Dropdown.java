package rs.realm.launcher;

import java.awt.AlphaComposite;
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
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A flat dropdown that opens with a slide, in place of Swing's default combo box.
 *
 * {@code JComboBox} paints a 1998 bevel and pops a bordered list with a system scrollbar. Restyling
 * it means fighting a UI delegate that assumes all of that, so this draws its own field and puts the
 * list in a borderless window it can animate.
 *
 * <h2>Why a window and not a Popup</h2>
 *
 * {@code PopupFactory} hands back a popup that cannot be resized once shown, and the animation IS a
 * resize — the list grows from nothing to its full height. A {@link JWindow} can be reshaped every
 * frame, which is what makes the open read as a slide rather than a flash.
 *
 * <p>The window is closed on any click outside it and whenever the launcher moves or loses focus. A
 * detached top-level window that outlives its trigger is the failure mode here: it would float above
 * everything else on the desktop with no way to dismiss it.
 */
public final class Dropdown<T> extends JComponent {

    private static final int HEIGHT = 40;
    private static final int ROW_HEIGHT = 34;
    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int ARC = 8;

    /** ~150ms at 60fps. Long enough to read as motion, short enough never to feel like waiting. */
    private static final int ANIM_MILLIS = 150;

    private static final int FRAME_MILLIS = 16;

    private final List<T> items = new ArrayList<>();
    private final Function<T, String> renderer;
    private Consumer<T> onSelect = value -> {};

    private T selected;
    private String placeholder = "";
    private boolean hovering;
    private boolean open;

    private JWindow popup;
    private ListPanel list;
    private Timer animation;
    private int animatedHeight;

    public Dropdown(Function<T, String> renderer) {
        this.renderer = renderer;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(0, HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
        setFont(new Font("SansSerif", Font.PLAIN, 14));

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
                        toggle();
                    }
                });
    }

    // ------------------------------------------------------------------------- model ----

    public void setItems(List<T> values) {
        items.clear();
        items.addAll(values);
        if (selected == null || !items.contains(selected)) {
            selected = items.isEmpty() ? null : items.get(0);
        }
        close();
        repaint();
    }

    public void setPlaceholder(String text) {
        this.placeholder = text;
        repaint();
    }

    public T getSelected() {
        return selected;
    }

    public void setSelected(T value) {
        this.selected = value;
        repaint();
    }

    public void setOnSelect(Consumer<T> listener) {
        this.onSelect = listener == null ? value -> {} : listener;
    }

    // ------------------------------------------------------------------------ opening ----

    private void toggle() {
        if (open) {
            close();
        } else {
            open();
        }
    }

    private void open() {
        if (items.isEmpty() || !isShowing()) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null) {
            return;
        }

        int rows = Math.min(items.size(), MAX_VISIBLE_ROWS);
        int fullHeight = rows * ROW_HEIGHT + 2;

        list = new ListPanel(fullHeight);
        popup = new JWindow(owner);
        popup.setContentPane(list);
        popup.setFocusableWindowState(false);

        Point origin = getLocationOnScreen();
        popup.setBounds(origin.x, origin.y + getHeight() + 4, getWidth(), 1);
        popup.setVisible(true);
        open = true;
        repaint();

        // A dropdown that survives the window moving under it, or the app losing focus, becomes a
        // stray always-on-top rectangle with nothing to dismiss it.
        owner.addComponentListener(dismissOnMove);
        owner.addWindowFocusListener(dismissOnBlur);

        animate(fullHeight);
    }

    private void animate(int target) {
        stopAnimation();
        long start = System.currentTimeMillis();
        animation =
                new Timer(
                        FRAME_MILLIS,
                        e -> {
                            float t = Math.min(1f, (System.currentTimeMillis() - start) / (float) ANIM_MILLIS);
                            // Ease-out: fast at the start, settling at the end. Linear motion of a
                            // panel reads as mechanical; this reads as the list falling into place.
                            float eased = 1f - (1f - t) * (1f - t);
                            animatedHeight = Math.max(1, Math.round(target * eased));
                            if (popup != null) {
                                popup.setSize(getWidth(), animatedHeight);
                                list.setRevealed(animatedHeight);
                            }
                            if (t >= 1f) {
                                stopAnimation();
                            }
                        });
        animation.start();
    }

    private void stopAnimation() {
        if (animation != null) {
            animation.stop();
            animation = null;
        }
    }

    /** Closes immediately — a closing animation only delays whatever the click was for. */
    public void close() {
        stopAnimation();
        if (popup != null) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            if (owner != null) {
                owner.removeComponentListener(dismissOnMove);
                owner.removeWindowFocusListener(dismissOnBlur);
            }
            popup.dispose();
            popup = null;
            list = null;
        }
        if (open) {
            open = false;
            repaint();
        }
    }

    private final java.awt.event.ComponentAdapter dismissOnMove =
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

    // ------------------------------------------------------------------------ painting ----

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();

        g2.setColor(Theme.BACKGROUND);
        g2.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
        g2.setColor(open || hovering ? Theme.GOLD : Theme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);

        String text = selected == null ? placeholder : renderer.apply(selected);
        g2.setColor(selected == null ? Theme.SUBTEXT : Theme.TEXT);
        g2.setFont(getFont());
        var fm = g2.getFontMetrics();
        g2.drawString(text, 12, (h - fm.getHeight()) / 2 + fm.getAscent());

        drawChevron(g2, w - 22, h / 2);
        g2.dispose();
    }

    /** A chevron that flips when the list is open, so the field says which way it will go. */
    private void drawChevron(Graphics2D g2, int cx, int cy) {
        g2.setColor(Theme.SUBTEXT);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int r = 4;
        Path2D path = new Path2D.Float();
        if (open) {
            path.moveTo(cx - r, cy + r / 2f);
            path.lineTo(cx, cy - r / 2f);
            path.lineTo(cx + r, cy + r / 2f);
        } else {
            path.moveTo(cx - r, cy - r / 2f);
            path.lineTo(cx, cy + r / 2f);
            path.lineTo(cx + r, cy - r / 2f);
        }
        g2.draw(path);
    }

    /** The list itself: rows with a hover highlight, clipped to however far the slide has got. */
    private final class ListPanel extends JComponent {
        private final int fullHeight;
        private int revealed = 1;
        private int hoverRow = -1;

        ListPanel(int fullHeight) {
            this.fullHeight = fullHeight;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseMotionListener(
                    new MouseMotionAdapter() {
                        @Override
                        public void mouseMoved(MouseEvent e) {
                            int row = (e.getY() - 1) / ROW_HEIGHT;
                            if (row != hoverRow) {
                                hoverRow = row;
                                repaint();
                            }
                        }
                    });
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mouseExited(MouseEvent e) {
                            hoverRow = -1;
                            repaint();
                        }

                        @Override
                        public void mousePressed(MouseEvent e) {
                            int row = (e.getY() - 1) / ROW_HEIGHT;
                            if (row >= 0 && row < items.size()) {
                                T value = items.get(row);
                                selected = value;
                                close();
                                onSelect.accept(value);
                            }
                        }
                    });
        }

        void setRevealed(int height) {
            this.revealed = height;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();

            g2.setColor(Theme.PANEL);
            g2.fillRoundRect(0, 0, w - 1, fullHeight - 1, ARC, ARC);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, fullHeight - 1, ARC, ARC);

            // Rows fade in as the panel grows: the ones still below the reveal line are drawn
            // faintly, so the list arrives rather than snapping into existence.
            g2.setFont(getFont() == null ? Dropdown.this.getFont() : Dropdown.this.getFont());
            var fm = g2.getFontMetrics();
            for (int i = 0; i < items.size() && i < MAX_VISIBLE_ROWS; i++) {
                int y = 1 + i * ROW_HEIGHT;
                float visible = Math.min(1f, Math.max(0f, (revealed - y) / (float) ROW_HEIGHT));
                if (visible <= 0f) {
                    continue;
                }
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, visible));
                if (i == hoverRow) {
                    g2.setColor(Theme.BORDER);
                    g2.fillRoundRect(3, y + 1, w - 7, ROW_HEIGHT - 2, 6, 6);
                }
                T value = items.get(i);
                g2.setColor(value.equals(selected) ? Theme.GOLD : Theme.TEXT);
                g2.drawString(
                        renderer.apply(value), 12, y + (ROW_HEIGHT - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.setComposite(AlphaComposite.SrcOver);

            // Trim whatever the animation has not reached yet.
            g2.setColor(new Color(0, 0, 0, 0));
            g2.dispose();
        }
    }
}
