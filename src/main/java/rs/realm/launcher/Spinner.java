package rs.realm.launcher;

import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * Something turning round: a set of frames from {@link Renders}. Still until the mouse is over it,
 * then it turns slowly; dragging turns it by hand. Drawn standing on the bottom edge (a character) or
 * in the middle (an item). Only this component repaints while it turns.
 */
final class Spinner extends JComponent {
    /** A full turn while hovered takes this long. */
    private static final double TURN_SECONDS = 9;
    /** A drag of this many pixels turns it one frame (of a 120-frame turn; scaled to the set). */
    private static final double DRAG_PX_PER_TURN = 480;

    private final boolean standing;
    private final Timer timer;
    private BufferedImage[] frames;
    private double frame;
    private boolean hovering;
    private boolean dragging;
    private int dragX;
    private double dragFrom;
    private long lastTick;

    Spinner(boolean standing) {
        this.standing = standing;
        setOpaque(false);
        timer = new Timer(33, e -> tick());
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovering = true;
                start();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovering = false;
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dragging = true;
                dragX = e.getX();
                dragFrom = frame;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (frames != null) {
                    frame = wrap(dragFrom - (e.getX() - dragX) * frames.length / DRAG_PX_PER_TURN);
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Lets a parent forward hovering (a card hovered anywhere turns its picture). */
    void setHovering(boolean on) {
        hovering = on;
        if (on) {
            start();
        }
    }

    void setFrames(BufferedImage[] frames) {
        this.frames = frames;
        frame = 0;
        setCursor(Cursor.getPredefinedCursor(frames == null ? Cursor.DEFAULT_CURSOR : Cursor.MOVE_CURSOR));
        repaint();
    }

    boolean hasFrames() {
        return frames != null;
    }

    private void start() {
        if (frames != null && !timer.isRunning()) {
            lastTick = System.currentTimeMillis();
            timer.start();
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        double dt = (now - lastTick) / 1000.0;
        lastTick = now;
        if (frames == null) {
            timer.stop();
            return;
        }
        if (dragging) {
            return;
        }
        if (hovering) {
            frame = wrap(frame + dt * frames.length / TURN_SECONDS);
        } else {
            // Not hovered: carry on round to the front, then rest there.
            double toFront = frames.length - frame;
            if (frame < 0.5 || toFront < 0.5) {
                frame = 0;
                timer.stop();
            } else {
                frame = wrap(frame + Math.min(toFront, dt * frames.length / TURN_SECONDS * 2.5));
            }
        }
        repaint();
    }

    private double wrap(double f) {
        int n = frames == null ? 1 : frames.length;
        return ((f % n) + n) % n;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (frames == null) {
            return;
        }
        BufferedImage img = frames[((int) Math.round(frame)) % frames.length];
        int x = (getWidth() - img.getWidth()) / 2;
        int y = standing ? getHeight() - img.getHeight() : (getHeight() - img.getHeight()) / 2;
        g.drawImage(img, x, y, null);
    }
}
