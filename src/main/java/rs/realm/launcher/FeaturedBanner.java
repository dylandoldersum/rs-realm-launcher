package rs.realm.launcher;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/**
 * The Home tab's featured news: the featured update's picture (set on the admin website; a built-in
 * one when there is none) filling the space under the heroes (cropped to it,
 * its right side kept, where the picture's subject is), in the game's steel interface border
 * (its own sprites, 310-315, drawn pixel for pixel at [SCALE]), opening the News tab when clicked.
 */
final class FeaturedBanner extends JPanel {
    private static final int SCALE = 2;
    private static final BufferedImage TOP_LEFT = sprite("top_left");
    private static final BufferedImage TOP_RIGHT = sprite("top_right");
    private static final BufferedImage BOTTOM_LEFT = sprite("bottom_left");
    private static final BufferedImage BOTTOM_RIGHT = sprite("bottom_right");
    private static final BufferedImage HORIZONTAL = sprite("horizontal");
    private static final BufferedImage VERTICAL = sprite("vertical");

    /** The picture built in (shown when no update is featured). */
    private final BufferedImage builtIn;
    private BufferedImage source;
    private BufferedImage cached;
    private boolean hovering;

    FeaturedBanner(String resource, Runnable open) {
        setOpaque(false);
        BufferedImage img = null;
        try {
            var url = getClass().getResource(resource);
            if (url != null) {
                img = ImageIO.read(url);
            }
        } catch (Exception ignored) {
            // No picture: the space stays empty rather than the launcher failing.
        }
        builtIn = img;
        source = img;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
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
            public void mouseClicked(MouseEvent e) {
                open.run();
            }
        });
    }

    /** Shows [picture] (the featured update's), or the built-in one again for null. */
    void setPicture(BufferedImage picture) {
        source = picture == null ? builtIn : picture;
        cached = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        if (source == null || w <= 0 || h <= 0) {
            return;
        }
        if (cached == null || cached.getWidth() != w || cached.getHeight() != h) {
            cached = cover(w, h);
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.drawImage(cached, 0, 0, null);
        if (hovering) {
            g.setColor(new Color(255, 255, 255, 14));
            g.fillRect(0, 0, w, h);
        }
        border(g, w, h);
        g.dispose();
    }

    /** The steel border: its edges tiled along the sides, its corners over their ends. */
    private static void border(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (HORIZONTAL != null) {
            int tw = HORIZONTAL.getWidth() * SCALE, th = HORIZONTAL.getHeight() * SCALE;
            for (int x = 0; x < w; x += tw) {
                g.drawImage(HORIZONTAL, x, 0, tw, th, null);
                // The sprite is the top edge (its light side in); the bottom is it upside down.
                g.drawImage(HORIZONTAL, x, h, tw, -th, null);
            }
        }
        if (VERTICAL != null) {
            int tw = VERTICAL.getWidth() * SCALE, th = VERTICAL.getHeight() * SCALE;
            for (int y = 0; y < h; y += th) {
                // The sprite is the right edge; the left is it mirrored.
                g.drawImage(VERTICAL, tw, y, -tw, th, null);
                g.drawImage(VERTICAL, w - tw, y, tw, th, null);
            }
        }
        corner(g, TOP_LEFT, false, false, w, h);
        corner(g, TOP_RIGHT, true, false, w, h);
        corner(g, BOTTOM_LEFT, false, true, w, h);
        corner(g, BOTTOM_RIGHT, true, true, w, h);
    }

    private static void corner(Graphics2D g, BufferedImage s, boolean right, boolean bottom, int w, int h) {
        if (s == null) {
            return;
        }
        int cw = s.getWidth() * SCALE, ch = s.getHeight() * SCALE;
        g.drawImage(s, right ? w - cw : 0, bottom ? h - ch : 0, cw, ch, null);
    }

    private static BufferedImage sprite(String name) {
        try {
            var url = FeaturedBanner.class.getResource("/border/" + name + ".png");
            return url == null ? null : ImageIO.read(url);
        } catch (Exception e) {
            return null;
        }
    }

    /** The picture scaled to cover [w] x [h]: cut from the left if too wide, top and bottom evenly if too tall. */
    private BufferedImage cover(int w, int h) {
        double scale = Math.max((double) w / source.getWidth(), (double) h / source.getHeight());
        int sw = (int) Math.ceil(source.getWidth() * scale), sh = (int) Math.ceil(source.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, w - sw, (h - sh) / 2, sw, sh, null);
        g.dispose();
        return out;
    }
}
