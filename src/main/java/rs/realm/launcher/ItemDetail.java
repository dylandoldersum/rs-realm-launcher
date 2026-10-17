package rs.realm.launcher;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * A store product up close, over the launcher: the player's character wearing or holding it (turning,
 * drag to turn), using it (its animation), or the item alone; and next to that its name, price,
 * description, who it is for and Buy now. Closed with the cross, Escape, or a click beside it.
 */
final class ItemDetail extends JComponent {
    private static final int W = 880;
    private static final int H = 560;
    private static final int STAGE_W = 460;
    private static final Font TAG = new Font("SansSerif", Font.BOLD, 11);
    private static final Font NAME = new Font("SansSerif", Font.BOLD, 28);
    private static final Font PRICE = new Font("SansSerif", Font.BOLD, 22);
    private static final Font BODY = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font PILL = new Font("SansSerif", Font.BOLD, 11);

    private enum View { WORN, USE, ITEM }

    private final Backend.StoreItem item;
    private final int pictureId;
    private final Backend.Store store;
    private final BiConsumer<Backend.StoreItem, Backend.Profile> buy;
    private final Spinner worn = new Spinner(true);
    private final Clip use = new Clip();
    private final Spinner alone = new Spinner(false);
    private final Dropdown<Backend.Profile> deliverTo = new Dropdown<>(p -> Renders.clean(p.displayName()));
    private final Theme.AccentButton buyButton;
    private final List<Rectangle> pills = new ArrayList<>();
    private final List<View> pillViews = new ArrayList<>();
    private Rectangle close;
    private View view = View.WORN;
    private boolean hasWorn;
    private boolean hasUse;
    /** A cosmetic: its picture is a clip of its own (played as an animation, large), not an item turning. */
    private boolean clipItem;
    private String slot = "";

    private ItemDetail(Backend.StoreItem item, int pictureId, Backend.Store store, List<Backend.Profile> profiles,
            Backend.Profile chosen, BiConsumer<Backend.StoreItem, Backend.Profile> buy) {
        this.item = item;
        this.pictureId = pictureId;
        this.store = store;
        this.buy = buy;
        setLayout(null);
        setOpaque(false);
        add(worn);
        add(use);
        add(alone);
        deliverTo.setItems(profiles);
        if (chosen != null) {
            deliverTo.setSelected(chosen);
        }
        deliverTo.setOnSelect(p -> loadCharacter());
        add(deliverTo);
        buyButton = new Theme.AccentButton(store.payments() ? "Buy now" : store.testPurchases() ? "Give it (test)" : "Coming soon");
        buyButton.setAccent(Theme.GREEN, Theme.GREEN_HOVER);
        buyButton.setEnabled(store.canBuy());
        buyButton.addActionListener(e -> {
            Backend.Profile p = deliverTo.getSelected();
            if (p != null) {
                close();
            }
            buy.accept(item, p);
        });
        add(buyButton);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!modal().contains(e.getPoint()) || (close != null && close.contains(e.getPoint()))) {
                    close();
                    return;
                }
                for (int i = 0; i < pills.size(); i++) {
                    if (pills.get(i).contains(e.getPoint())) {
                        show(pillViews.get(i));
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                boolean hand = close != null && close.contains(e.getPoint());
                for (Rectangle r : pills) hand |= r.contains(e.getPoint());
                setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                close();
            }
        });

        new SwingWorker<Renders.Set, Void>() {
            @Override
            protected Renders.Set doInBackground() {
                return Renders.itemOrDefault(pictureId);
            }

            @Override
            protected void done() {
                try {
                    Renders.Set set = get();
                    // (delays: null for a picture that turns round, not an animation)
                    if (set != null && set.delays() != null && set.delays().length > 0) {
                        // A cosmetic's clip: played at its own pace, large, the only view.
                        clipItem = true;
                        hasUse = true;
                        hasWorn = false;
                        int[] delays = set.delays();
                        Renders.load(set, 400, frames -> {
                            useFrames = frames;
                            useDelays = delays;
                            show(View.USE);
                        });
                    } else if (set != null && "Cosmetics".equals(item.category())) {
                        // A cosmetic that does not move (eyes): the character turning round, large,
                        // the only view.
                        clipItem = true;
                        hasWorn = false;
                        hasUse = false;
                        Renders.load(set, 400, frames -> {
                            alone.setFrames(frames);
                            show(View.ITEM);
                        });
                    } else if (set != null) {
                        Renders.load(set, 220, frames -> {
                            alone.setFrames(frames);
                            repaint();
                        });
                    }
                } catch (Exception ignored) {
                    // the item alone stays empty
                }
            }
        }.execute();
        loadCharacter();
    }

    /** Opens [item] over the window that holds [owner]. */
    static void open(JComponent owner, Backend.StoreItem item, int pictureId, Backend.Store store, List<Backend.Profile> profiles,
            Backend.Profile chosen, BiConsumer<Backend.StoreItem, Backend.Profile> buy) {
        JRootPane root = SwingUtilities.getRootPane(owner);
        if (root == null) {
            return;
        }
        JLayeredPane layers = root.getLayeredPane();
        ItemDetail detail = new ItemDetail(item, pictureId, store, profiles, chosen, buy);
        // Under the title bar (where the tabs' area starts), over everything else.
        java.awt.Container area = owner.getParent() != null ? owner.getParent() : owner;
        int top = Math.max(0, SwingUtilities.convertPoint(area, 0, 0, layers).y);
        detail.setBounds(0, top, layers.getWidth(), layers.getHeight() - top);
        layers.add(detail, JLayeredPane.MODAL_LAYER);
        detail.requestFocusInWindow();
        layers.revalidate();
        layers.repaint();
    }

    private void close() {
        use.stop();
        java.awt.Container parent = getParent();
        if (parent != null) {
            parent.remove(this);
            parent.repaint();
        }
    }

    /** The chosen character's worn and use renders (any character's if it has none yet). */
    private void loadCharacter() {
        Backend.Profile p = deliverTo.getSelected();
        String who = p == null ? "" : p.displayName();
        new SwingWorker<Renders.Set[], Void>() {
            @Override
            protected Renders.Set[] doInBackground() {
                return new Renders.Set[] {Renders.wornOrDefault(pictureId, who), Renders.useOrDefault(pictureId, who)};
            }

            @Override
            protected void done() {
                try {
                    Renders.Set[] sets = get();
                    if (clipItem) {
                        return; // (a cosmetic has no worn or use pictures of its own)
                    }
                    hasWorn = sets[0] != null;
                    hasUse = sets[1] != null && item.useAnim() >= 0;
                    slot = sets[0] != null ? sets[0].slot() : "";
                    if (sets[0] != null) {
                        Renders.load(sets[0], 400, frames -> {
                            worn.setFrames(frames);
                            worn.setHovering(true);
                        });
                    }
                    if (hasUse) {
                        int[] delays = sets[1].delays();
                        Renders.load(sets[1], 400, frames -> {
                            if (view == View.USE) use.play(frames, delays);
                            useFrames = frames;
                            useDelays = delays;
                        });
                    }
                    if (!hasWorn && view == View.WORN) {
                        view = View.ITEM;
                    }
                    show(view);
                } catch (Exception ignored) {
                    // the item alone
                }
            }
        }.execute();
    }

    private BufferedImage[] useFrames;
    private int[] useDelays;

    private void show(View v) {
        view = v;
        worn.setVisible(v == View.WORN);
        alone.setVisible(v == View.ITEM);
        use.setVisible(v == View.USE);
        if (v == View.USE && useFrames != null) {
            use.play(useFrames, useDelays, !clipItem);
        } else if (v != View.USE) {
            use.stop();
        }
        alone.setHovering(v == View.ITEM);
        repaint();
    }

    private Rectangle modal() {
        return new Rectangle((getWidth() - W) / 2, Math.max(20, (getHeight() - H) / 2), W, H);
    }

    @Override
    public void doLayout() {
        Rectangle m = modal();
        Rectangle stage = new Rectangle(m.x, m.y, STAGE_W, H);
        worn.setBounds(stage.x + 20, stage.y + 40, STAGE_W - 40, H - 120);
        use.setBounds(stage.x + 20, stage.y + 40, STAGE_W - 40, H - 120);
        alone.setBounds(stage.x + 20, stage.y + 60, STAGE_W - 40, H - 160);
        int rx = m.x + STAGE_W + 30, rw = W - STAGE_W - 60;
        deliverTo.setBounds(rx, m.y + H - 154, rw, 38);
        buyButton.setBounds(rx, m.y + H - 104, rw, 50);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, getWidth(), getHeight());
        Rectangle m = modal();
        RoundRectangle2D box = new RoundRectangle2D.Float(m.x, m.y, W, H, 18, 18);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Float(m.x + 4, m.y + 10, W, H, 18, 18));
        g.setClip(box);
        // The stage: night above, the fire below.
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, m.y), new Point2D.Float(0, m.y + H), new float[] {0f, 0.68f, 1f},
                new Color[] {new Color(0x14, 0x18, 0x21), new Color(0x1d, 0x17, 0x12), new Color(0x2c, 0x1b, 0x10)}));
        g.fillRect(m.x, m.y, STAGE_W, H);
        int ground = m.y + 40 + H - 120 - 10;
        float r = 200;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(m.x + STAGE_W / 2f, ground), r, new float[] {0f, 0.45f, 1f},
                new Color[] {new Color(255, 140, 50, 120), new Color(200, 80, 30, 36), new Color(0, 0, 0, 0)}));
        g.fillRect(m.x, (int) (ground - r), STAGE_W, (int) (2 * r));
        if (view != View.ITEM) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(m.x + STAGE_W / 2f, ground), 90, new float[] {0f, 1f},
                    new Color[] {new Color(0, 0, 0, 150), new Color(0, 0, 0, 0)}));
            g.fillOval(m.x + STAGE_W / 2 - 90, ground - 12, 180, 24);
        }
        boolean nothing = (view == View.WORN && !worn.hasFrames()) || (view == View.ITEM && !alone.hasFrames());
        if (nothing) {
            g.setFont(BODY);
            g.setColor(Theme.SUBTEXT);
            centre(g, "The preview appears after your next game", m.x, STAGE_W, m.y + H / 2);
        }
        // The views to switch between.
        pills.clear();
        pillViews.clear();
        List<View> views = new ArrayList<>();
        if (hasWorn) views.add(View.WORN);
        if (hasUse) views.add(View.USE);
        if (!clipItem || !hasUse) views.add(View.ITEM);
        g.setFont(PILL);
        FontMetrics fm = g.getFontMetrics();
        String[] labels = {"⟲ Worn", clipItem ? "▶ Preview" : "▶ Use", "◇ Item"};
        int total = 0;
        for (View v : views) total += fm.stringWidth(labels[v.ordinal()]) + 22 + 8;
        int px = m.x + (STAGE_W - total + 8) / 2, py = m.y + H - 44;
        if (views.size() > 1) {
            for (View v : views) {
                String label = labels[v.ordinal()];
                int pw = fm.stringWidth(label) + 22;
                Rectangle rect = new Rectangle(px, py, pw, 26);
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 26, 26);
                g.setColor(v == view ? new Color(0xe0, 0xa0, 0x3a) : new Color(255, 255, 255, 45));
                g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 26, 26);
                g.setColor(v == view ? new Color(0xff, 0xcf, 0x73) : new Color(0xd6, 0xcd, 0xbb));
                g.drawString(label, rect.x + 11, rect.y + 17);
                pills.add(rect);
                pillViews.add(v);
                px += pw + 8;
            }
        }

        // The words.
        int rx = m.x + STAGE_W, rw = W - STAGE_W;
        g.setColor(new Color(0x20, 0x1d, 0x1a));
        g.fillRect(rx, m.y, rw, H);
        int x = rx + 30, w = rw - 60;
        g.setFont(TAG);
        g.setColor(new Color(0xe0, 0xa0, 0x3a));
        String tag = item.isPoints() ? "STORE POINTS"
                : "Cosmetics".equals(item.category()) ? "COSMETIC · ALL YOUR CHARACTERS"
                : slot.isEmpty() ? "ITEM" : "COSMETIC · " + slot.toUpperCase();
        g.drawString(tag, x, m.y + 42);
        g.setFont(NAME);
        g.setColor(new Color(0xff, 0xf1, 0xd6));
        g.drawString(item.name(), x, m.y + 78);
        g.setFont(PRICE);
        int after = SaleTag.price(g, item, x, m.y + 112, new Color(0xff, 0xc8, 0x5a));
        if (item.onSale()) {
            // A tag after the prices: how much is off.
            String off = item.percentOff() + "% OFF";
            g.setFont(TAG);
            int tw = g.getFontMetrics().stringWidth(off) + 16, th = 20, tx = after + 12, tyTop = m.y + 112 - 16;
            g.setPaint(new java.awt.GradientPaint(0, tyTop, new Color(0xe0, 0x4a, 0x36), 0, tyTop + th, new Color(0xa8, 0x28, 0x1e)));
            g.fillRoundRect(tx, tyTop, tw, th, th, th);
            g.setColor(Color.WHITE);
            g.drawString(off, tx + 8, tyTop + 14);
        }
        g.setFont(BODY);
        g.setColor(new Color(0xc9, 0xc1, 0xb1));
        int y = wrap(g, item.description(), x, m.y + 146, w, 20);
        g.setColor(new Color(0xb4, 0xab, 0x9b));
        y += 12;
        List<String> facts = new ArrayList<>();
        if (!slot.isEmpty()) facts.add("Worn in the " + slot.toLowerCase() + " slot");
        if (item.minRank() != null) facts.add(item.minRank() + " rank or higher only");
        facts.add("Counts towards your donator rank");
        for (String fact : facts) {
            g.fillOval(x + 2, y - 5, 4, 4);
            g.drawString(fact, x + 14, y);
            y += 20;
        }
        g.setFont(Theme.SMALL);
        g.setColor(Theme.SUBTEXT);
        g.drawString("Deliver to", x, m.y + H - 162);
        Backend.Profile p = deliverTo.getSelected();
        String fine = p == null ? "Choose a character" : "Arrives when " + Renders.clean(p.displayName()) + " next logs in · or type ::claim";
        centre(g, fine, rx, rw, m.y + H - 30);
        // The cross.
        close = new Rectangle(m.x + W - 40, m.y + 12, 26, 26);
        g.setColor(new Color(0x9a, 0x94, 0x8a));
        g.setStroke(new java.awt.BasicStroke(2f));
        g.drawLine(close.x + 7, close.y + 7, close.x + 19, close.y + 19);
        g.drawLine(close.x + 19, close.y + 7, close.x + 7, close.y + 19);
        g.setClip(null);
        g.setStroke(new java.awt.BasicStroke(1f));
        g.setColor(new Color(0x4a, 0x3f, 0x30));
        g.draw(box);
        g.dispose();
    }

    private static int wrap(Graphics2D g, String text, int x, int y, int width, int line) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = row.length() == 0 ? word : row + " " + word;
            if (fm.stringWidth(next) > width && row.length() > 0) {
                g.drawString(row.toString(), x, y);
                y += line;
                row = new StringBuilder(word);
            } else {
                row = new StringBuilder(next);
            }
        }
        if (row.length() > 0) {
            g.drawString(row.toString(), x, y);
            y += line;
        }
        return y;
    }

    private static void centre(Graphics2D g, String s, int x, int w, int y) {
        g.drawString(s, x + (w - g.getFontMetrics().stringWidth(s)) / 2, y);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(W, H);
    }
}
