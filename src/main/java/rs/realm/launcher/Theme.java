package rs.realm.launcher;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/** RuneLite-style dark palette + a flat rounded button used for the primary action. */
public final class Theme {
    private Theme() {}

    public static final Color BACKGROUND = new Color(0x22, 0x20, 0x1E);
    public static final Color PANEL = new Color(0x2B, 0x28, 0x25);
    public static final Color TITLE_BAR = new Color(0x1B, 0x19, 0x17);
    public static final Color BORDER = new Color(0x3C, 0x38, 0x34);
    public static final Color TRACK = new Color(0x17, 0x15, 0x13); // progress-bar track
    public static final Color TEXT = new Color(0xEC, 0xE9, 0xE3);
    public static final Color SUBTEXT = new Color(0x9A, 0x94, 0x8B);

    /**
     * Secondary text where the background is the artwork rather than a panel.
     *
     * Brighter than {@link #SUBTEXT}, which was picked against a flat dark panel and disappears over
     * a picture. Still clearly below {@link #TEXT} in the hierarchy — the point is to be readable,
     * not to compete with the button.
     */
    public static final Color OVERLAY_TEXT = new Color(0xD4, 0xCF, 0xC5);

    /** Cast under overlay text. Nearly opaque, because the artwork behind it can be pale. */
    public static final Color OVERLAY_SHADOW = new Color(0, 0, 0, 190);

    // State accents.
    public static final Color GOLD = new Color(0xC8, 0x8B, 0x3A); // download / update
    public static final Color GOLD_HOVER = new Color(0xDD, 0x9E, 0x48);
    public static final Color GREEN = new Color(0x5C, 0x8A, 0x3A); // play
    public static final Color GREEN_HOVER = new Color(0x6E, 0xA0, 0x46);
    public static final Color RED = new Color(0xB0, 0x4A, 0x3E); // error / retry

    public static final Font H1 = new Font("SansSerif", Font.BOLD, 22);
    public static final Font BODY = new Font("SansSerif", Font.PLAIN, 12);
    public static final Font BUTTON = new Font("SansSerif", Font.BOLD, 16);
    public static final Font SMALL = new Font("SansSerif", Font.PLAIN, 11);

    /**
     * A label that reads over artwork, by casting a shadow under itself.
     *
     * Brightening the colour alone does not fix this: the sidebar sits on a picture whose luminance
     * changes from a pale snowfield to a dark dragon within the same column, so ANY single colour
     * washes out somewhere down it. A dark offset copy gives every glyph its own edge, which works
     * regardless of what happens to be behind it.
     *
     * Draws its own text rather than deferring to {@link JLabel}, because the shadow has to go down
     * first. That means it handles plain single-line text only — no icons, no HTML — which is all
     * these labels are.
     */
    public static final class OverlayLabel extends JLabel {

        public OverlayLabel(String text, int horizontalAlignment) {
            super(text, horizontalAlignment);
            setForeground(OVERLAY_TEXT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            String text = getText();
            if (text == null || text.isBlank()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());

            FontMetrics metrics = g2.getFontMetrics();
            Insets insets = getInsets();
            int available = getWidth() - insets.left - insets.right;
            int textWidth = metrics.stringWidth(text);
            int x =
                    switch (getHorizontalAlignment()) {
                        case SwingConstants.CENTER -> insets.left + (available - textWidth) / 2;
                        case SwingConstants.RIGHT -> getWidth() - insets.right - textWidth;
                        default -> insets.left;
                    };
            int y =
                    insets.top
                            + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2
                            + metrics.getAscent();

            g2.setColor(OVERLAY_SHADOW);
            g2.drawString(text, x + 1, y + 1);
            g2.setColor(getForeground());
            g2.drawString(text, x, y);
            g2.dispose();
        }
    }

    /** A flat, rounded, hover-aware button with a solid accent fill. */
    public static final class AccentButton extends JButton {
        private Color base = GOLD;
        private Color hover = GOLD_HOVER;
        private boolean hovering;

        public AccentButton(String text) {
            super(text);
            setForeground(Color.WHITE);
            setFont(BUTTON);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(0, 46));
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
                });
        }

        public void setAccent(Color base, Color hover) {
            this.base = base;
            this.hover = hover;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = !isEnabled() ? BORDER : (hovering ? hover : base);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setColor(isEnabled() ? Color.WHITE : SUBTEXT);
            g2.setFont(getFont());
            var fm = g2.getFontMetrics();
            String t = getText();
            int tx = (getWidth() - fm.stringWidth(t)) / 2;
            int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(t, tx, ty);
            g2.dispose();
        }
    }
}
