package rs.realm.launcher;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;

/** How a product on sale looks in the store: a "20% OFF" ribbon over the corner, and the old price struck through. */
final class SaleTag {
    static final Color SALE = new Color(0xff, 0x8a, 0x5c);
    private static final Color WAS = new Color(0x8a, 0x84, 0x7b);

    private SaleTag() {}

    /** The ribbon across the top-left corner of a [w] x [h] card (rounded by [arc]). */
    static void ribbon(Graphics2D graphics, int w, int h, int arc, int percentOff) {
        Graphics2D g = (Graphics2D) graphics.create();
        Shape card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc);
        g.clip(card);
        String text = percentOff + "% OFF";
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int band = 20, length = Math.max(130, fm.stringWidth(text) + 70);
        // A band at 45 degrees, its middle on a point in from the corner.
        AffineTransform at = new AffineTransform();
        at.translate(34, 34);
        at.rotate(-Math.PI / 4);
        g.transform(at);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(-length / 2, -band / 2 + 2, length, band);
        g.setPaint(new GradientPaint(0, -band / 2f, new Color(0xe0, 0x4a, 0x36), 0, band / 2f, new Color(0xa8, 0x28, 0x1e)));
        g.fillRect(-length / 2, -band / 2, length, band);
        g.setColor(new Color(255, 255, 255, 60));
        g.drawLine(-length / 2, -band / 2, length / 2, -band / 2);
        g.setColor(Color.WHITE);
        g.drawString(text, -fm.stringWidth(text) / 2, (fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
    }

    /**
     * The price at ([x], [y]) in the current font: on sale, the sale's price and the old one struck
     * through after it, smaller. Returns the x after it.
     */
    static int price(Graphics2D g, Backend.StoreItem item, int x, int y, Color normal) {
        if (!item.onSale()) {
            g.setColor(normal);
            g.drawString(item.price(), x, y);
            return x + g.getFontMetrics().stringWidth(item.price());
        }
        Font font = g.getFont();
        g.setColor(SALE);
        g.drawString(item.price(), x, y);
        x += g.getFontMetrics().stringWidth(item.price()) + 8;
        g.setFont(font.deriveFont(Font.PLAIN, font.getSize2D() * 0.8f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(WAS);
        g.drawString(item.was(), x, y);
        int strike = y - fm.getAscent() / 3;
        g.drawLine(x - 1, strike, x + fm.stringWidth(item.was()) + 1, strike);
        x += fm.stringWidth(item.was());
        g.setFont(font);
        return x;
    }
}
