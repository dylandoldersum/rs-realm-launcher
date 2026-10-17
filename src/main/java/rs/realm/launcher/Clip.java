package rs.realm.launcher;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * An animation from {@link Renders} (a character using an item): its frames at the game's own
 * timing, again and again with a short rest between. Drawn standing on the bottom edge.
 */
final class Clip extends JComponent {
    private static final int TICK_MS = 20;
    private static final int REST_MS = 900;

    private final Timer timer;
    private BufferedImage[] frames;
    private int[] delays;
    private int frame;
    private long frameEnds;
    /** The rest after the last frame (none for a loop that runs on, like an effect's). */
    private int rest = REST_MS;

    Clip() {
        setOpaque(false);
        timer = new Timer(15, e -> tick());
    }

    void play(BufferedImage[] frames, int[] delays) {
        play(frames, delays, true);
    }

    /** [rests]: a short rest before playing again (an action); else round and round (a loop). */
    void play(BufferedImage[] frames, int[] delays, boolean rests) {
        rest = rests ? REST_MS : 0;
        this.frames = frames;
        this.delays = delays;
        frame = 0;
        frameEnds = System.currentTimeMillis() + lengthOf(0);
        timer.start();
        repaint();
    }

    void stop() {
        timer.stop();
        frames = null;
        repaint();
    }

    private long lengthOf(int i) {
        int ticks = delays != null && i < delays.length ? Math.max(1, delays[i]) : 4;
        return (long) ticks * TICK_MS + (i == frames.length - 1 ? rest : 0);
    }

    private void tick() {
        if (frames == null || !isShowing()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < frameEnds) {
            return;
        }
        frame = (frame + 1) % frames.length;
        frameEnds = now + lengthOf(frame);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (frames == null) {
            return;
        }
        BufferedImage img = frames[frame];
        g.drawImage(img, (getWidth() - img.getWidth()) / 2, getHeight() - img.getHeight(), null);
    }
}
