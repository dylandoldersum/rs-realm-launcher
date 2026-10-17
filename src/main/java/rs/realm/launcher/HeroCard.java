package rs.realm.launcher;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * One character in the Home tab's showcase: standing by a fire on its own little stage, turning when
 * the mouse is over it (or dragged round), with its name and numbers under it. A character the game
 * has not drawn yet stands as a dark silhouette until its next session.
 */
final class HeroCard extends JComponent {
    static final int WIDTH = 172;
    static final int HEIGHT = 250;
    /** The strip at the bottom with the name and numbers. */
    private static final int INFO_H = 72;
    private static final int FIGURE_H = HEIGHT - INFO_H - 26;

    private static final Color EDGE = new Color(0x34, 0x2e, 0x27);
    private static final Color EDGE_HOVER = new Color(0x8a, 0x6a, 0x38);
    private static final Font NAME = new Font("SansSerif", Font.BOLD, 14);
    private static final Font META = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font META_BOLD = new Font("SansSerif", Font.BOLD, 10);
    private static final Font PILL = new Font("SansSerif", Font.BOLD, 10);
    private static final Font AMOUNT = new Font("SansSerif", Font.BOLD, 9);

    private final Backend.Profile profile;
    private final Spinner figure = new Spinner(true);
    private BufferedImage silhouette;
    private boolean hovering;

    HeroCard(Backend.Profile profile) {
        this.profile = profile;
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(new Dimension(WIDTH, HEIGHT));
        setMaximumSize(new Dimension(WIDTH, HEIGHT));
        setLayout(null);
        figure.setBounds(0, 14, WIDTH, FIGURE_H + 12);
        add(figure);
        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // Leaving the card for the figure on it is not leaving the card.
                if (!contains(e.getPoint())) {
                    setHovered(false);
                }
            }
        };
        addMouseListener(hover);
        figure.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                java.awt.Point p = javax.swing.SwingUtilities.convertPoint(figure, e.getPoint(), HeroCard.this);
                if (!contains(p)) {
                    setHovered(false);
                }
            }
        });
    }

    Backend.Profile profile() {
        return profile;
    }

    String key() {
        return Renders.clean(profile.displayName()).toLowerCase(java.util.Locale.ROOT);
    }

    /** The character's render (or null). */
    void setFrames(BufferedImage[] frames) {
        figure.setFrames(frames);
        repaint();
    }

    boolean hasFrames() {
        return figure.hasFrames();
    }

    /** Somebody's front view, darkened, for a character with no render of its own. */
    void setSilhouette(BufferedImage front) {
        if (front == null) {
            silhouette = null;
        } else {
            BufferedImage s = new BufferedImage(front.getWidth(), front.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < front.getHeight(); y++) {
                for (int x = 0; x < front.getWidth(); x++) {
                    int a = (front.getRGB(x, y) >>> 24) * 120 / 255;
                    s.setRGB(x, y, a << 24 | 0x08090c);
                }
            }
            silhouette = s;
        }
        repaint();
    }

    private void setHovered(boolean on) {
        if (hovering != on) {
            hovering = on;
            figure.setHovering(on);
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14);
        g.clip(shape);

        // Night above, warm by the fire below.
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(0, h),
                new float[] {0f, 0.62f, 1f},
                new Color[] {new Color(0x15, 0x19, 0x21), new Color(0x1c, 0x17, 0x12), new Color(0x2a, 0x1a, 0x10)}));
        g.fillRect(0, 0, w, h);
        int ground = 14 + FIGURE_H + 12 - 6;
        float r = hovering ? 128 : 112;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, ground), r,
                new float[] {0f, 0.45f, 1f},
                new Color[] {new Color(255, 140, 50, hovering ? 120 : 92), new Color(200, 80, 30, 34), new Color(0, 0, 0, 0)}));
        g.fillRect(0, (int) (ground - r), w, (int) (2 * r));
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, ground), 62,
                new float[] {0f, 1f}, new Color[] {new Color(0, 0, 0, 140), new Color(0, 0, 0, 0)}));
        g.fillOval(w / 2 - 62, ground - 10, 124, 20);

        if (!figure.hasFrames()) {
            if (silhouette != null) {
                int sh = FIGURE_H - 8;
                int sw = silhouette.getWidth() * sh / silhouette.getHeight();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(silhouette, (w - sw) / 2, ground + 6 - sh, sw, sh, null);
            }
            g.setFont(META);
            g.setColor(Theme.SUBTEXT);
            drawCentred(g, "Play " + Renders.clean(profile.displayName()), w, 120);
            drawCentred(g, "to see them here", w, 136);
        }
        g.dispose();
    }

    /** The name, the numbers and the class pill go over the figure. */
    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.clip(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));

        int top = h - INFO_H;
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, top - 10), new Point2D.Float(0, top + 26),
                new float[] {0f, 1f}, new Color[] {new Color(0, 0, 0, 0), new Color(0, 0, 0, 205)}));
        g.fillRect(0, top - 10, w, h - top + 10);

        Backend.Stats stats = profile.stats();
        g.setFont(NAME);
        g.setColor(hovering ? new Color(0xff, 0xcf, 0x73) : Theme.TEXT);
        g.drawString(Renders.clean(profile.displayName()), 13, top + 24);
        if (stats != null) {
            int y = top + 41;
            int x = drawPair(g, 13, y, "Combat ", Integer.toString(stats.combatLevel()));
            x = drawPair(g, x, y, " · Total ", String.format(java.util.Locale.US, "%,d", stats.totalLevel()));
            g.setFont(META);
            g.setColor(new Color(0x9a, 0x94, 0x8b));
            if (stats.playtimeMinutes() > 0) {
                g.drawString(playtime(stats.playtimeMinutes()) + " played", 13, top + 57);
            }
            if (stats.className() != null) {
                pill(g, stats.className(), w);
            }
        } else {
            g.setFont(META);
            g.setColor(Theme.SUBTEXT);
            g.drawString("Not played yet", 13, top + 41);
        }

        Backend.Donor donor = profile.donor();
        if (donor != null && donor.rank() != null) {
            String amount = donor.cents() > 0 ? donor.amount() : "";
            rankBadge(g, donor.rank(), amount, new Color(donor.colour()));
        }

        g.setColor(hovering ? EDGE_HOVER : EDGE);
        g.setClip(null);
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));
        g.dispose();
    }

    private static int drawPair(Graphics2D g, int x, int y, String label, String value) {
        g.setFont(META);
        g.setColor(new Color(0xb4, 0xab, 0x9b));
        g.drawString(label, x, y);
        x += g.getFontMetrics().stringWidth(label);
        g.setFont(META_BOLD);
        g.setColor(Color.WHITE);
        g.drawString(value, x, y);
        return x + g.getFontMetrics().stringWidth(value);
    }

    private static void pill(Graphics2D g, String text, int w) {
        g.setFont(PILL);
        FontMetrics fm = g.getFontMetrics();
        int pw = fm.stringWidth(text) + 16, ph = 18;
        int x = w - pw - 10, y = 10;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x, y, pw, ph, ph, ph);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(x, y, pw, ph, ph, ph);
        g.setColor(new Color(0xd8, 0xcf, 0xbd));
        g.drawString(text, x + 8, y + 13);
    }

    /**
     * The donator rank, top left: only its glowing gem, on a dark round badge in the rank's colour
     * (no name: the gem says it); the amount donated, small, beneath it.
     */
    private static void rankBadge(Graphics2D g, String rank, String amount, Color colour) {
        int d = 22, x = 10, y = 10;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillOval(x, y, d, d);
        g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 120));
        g.drawOval(x, y, d, d);
        // The gem: a soft glow, then a diamond with a light upper half.
        int gem = 8;
        int gx = x + d / 2, gy = y + d / 2;
        for (int r = 7; r >= 4; r--) {
            g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 18));
            g.fillOval(gx - r, gy - r, r * 2, r * 2);
        }
        java.awt.Polygon diamond = new java.awt.Polygon(
                new int[] {gx, gx + gem / 2, gx, gx - gem / 2}, new int[] {gy - gem / 2 - 1, gy, gy + gem / 2 + 1, gy}, 4);
        g.setColor(colour);
        g.fillPolygon(diamond);
        g.setColor(new Color(255, 255, 255, 110));
        g.fillPolygon(new int[] {gx, gx + gem / 2, gx - gem / 2}, new int[] {gy - gem / 2 - 1, gy, gy}, 3);
        if (!amount.isEmpty()) {
            g.setFont(AMOUNT);
            int aw = g.getFontMetrics().stringWidth(amount);
            int ax = Math.max(4, gx - aw / 2);
            g.setColor(new Color(0, 0, 0, 170));
            g.drawString(amount, ax + 1, y + d + 12);
            g.setColor(new Color(0xf2, 0xc2, 0x5a));
            g.drawString(amount, ax, y + d + 11);
        }
    }

    private static void drawCentred(Graphics2D g, String text, int w, int y) {
        g.drawString(text, (w - g.getFontMetrics().stringWidth(text)) / 2, y);
    }

    private static String playtime(int minutes) {
        if (minutes < 60) return minutes + "m";
        if (minutes < 60 * 24) return minutes / 60 + "h " + minutes % 60 + "m";
        return minutes / (60 * 24) + "d " + minutes / 60 % 24 + "h";
    }

    /** The height the figure's frames are scaled to. */
    static int figureHeight() {
        return FIGURE_H;
    }
}
