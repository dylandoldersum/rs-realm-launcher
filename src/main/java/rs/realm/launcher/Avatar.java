package rs.realm.launcher;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingWorker;

/**
 * Loads remote images without ever blocking the UI thread.
 *
 * Both things this fetches — Discord avatars and news thumbnails — are decoration. A slow or dead
 * CDN must cost the player nothing, so every load runs on a worker and every failure leaves the
 * placeholder in place rather than surfacing an error. Discord attachment URLs are also signed and
 * expire, so a thumbnail that 404s tomorrow is normal, not a bug.
 */
public final class Avatar {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Avatar() {}

    /** Replaces [label]'s icon with a circular avatar once it arrives. */
    public static void loadCircular(JLabel label, String url, int size, String fallbackInitial) {
        label.setIcon(new ImageIcon(monogram(size, fallbackInitial)));
        if (url == null || url.isBlank()) {
            return;
        }
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                BufferedImage image = fetch(url);
                return image == null ? null : circle(image, size);
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        label.setIcon(new ImageIcon(image));
                    }
                } catch (Exception e) {
                    // Keep the monogram. Nothing here is worth telling the player about.
                }
            }
        }.execute();
    }

    /**
     * Fetches an image and hands it over on the UI thread, at its original size.
     *
     * Deliberately not scaled here. A news card's width is whatever the window gives it, and that
     * changes as the launcher is resized — so scaling belongs at paint time, where the real width is
     * known, not at load time where it would have to be guessed.
     */
    public static void load(String url, java.util.function.Consumer<BufferedImage> onLoaded) {
        if (url == null || url.isBlank()) {
            return;
        }
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return fetch(url);
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        onLoaded.accept(image);
                    }
                } catch (Exception e) {
                    // Card keeps its plain background. Discord attachment URLs are signed and
                    // expire, so an older post failing to load is normal rather than broken.
                }
            }
        }.execute();
    }

    private static BufferedImage fetch(String url) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            return ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Scales to fill and centre-crops, so a wide screenshot does not letterbox the card. */
    public static BufferedImage cover(BufferedImage source, int width, int height) {
        double scale =
                Math.max((double) width / source.getWidth(), (double) height / source.getHeight());
        int w = (int) Math.ceil(source.getWidth() * scale);
        int h = (int) Math.ceil(source.getHeight() * scale);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, (width - w) / 2, (height - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage circle(BufferedImage source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setClip(new Ellipse2D.Float(0, 0, size, size));
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static BufferedImage monogram(int size, String initial) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.BORDER);
        g.fillOval(0, 0, size, size);
        String text =
                initial == null || initial.isBlank()
                        ? "?"
                        : initial.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, (int) (size * 0.5)));
        var fm = g.getFontMetrics();
        g.drawString(
                text, (size - fm.stringWidth(text)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return out;
    }
}
