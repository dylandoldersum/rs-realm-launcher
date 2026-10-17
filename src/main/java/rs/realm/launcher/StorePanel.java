package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * The Store tab: the game's store products (the same ones as in game), each bought for one of the
 * player's characters, chosen at the top. Payment is Stripe's checkout page in the browser; the item
 * arrives when that character next logs in. The items turn round when the mouse is over them, from
 * pictures the game client draws ({@link Renders}).
 */
final class StorePanel extends JPanel {
    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 19);
    private static final Font SECTION = new Font("SansSerif", Font.BOLD, 14);
    /** Coins, standing in for the store-points packages' picture. */
    private static final int POINTS_PICTURE = 995;

    private final Backend backend;
    private final Dropdown<Backend.Profile> deliverTo = new Dropdown<>(p -> Renders.clean(p.displayName()));
    private final JPanel content = new JPanel();
    private final JLabel notice = new JLabel(" ", SwingConstants.LEFT);
    private final List<ItemCard> cards = new ArrayList<>();
    private Backend.Store store;
    private boolean loading;
    /** The open tab: "All", or one of the products' categories. */
    private String category = ALL;
    private static final String ALL = "All";
    /** The tabs' order (others after, as they come). */
    private static final List<String> CATEGORY_ORDER = List.of("Cosmetics", "Rares", "Utilities");
    private List<Backend.Profile> profiles = List.of();

    StorePanel(Backend backend) {
        super(new BorderLayout());
        this.backend = backend;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 26, 0, 16));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 10));
        JPanel words = new JPanel();
        words.setOpaque(false);
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Store");
        title.setFont(TITLE);
        title.setForeground(new Color(0xf1, 0xea, 0xd9));
        JLabel hint = new JLabel("Items go to the character you choose; cosmetics are yours on every character.");
        hint.setFont(Theme.BODY);
        hint.setForeground(Theme.SUBTEXT);
        words.add(title);
        words.add(Box.createVerticalStrut(3));
        words.add(hint);
        head.add(words, BorderLayout.WEST);

        JPanel who = new JPanel(new BorderLayout(8, 0));
        who.setOpaque(false);
        JLabel label = new JLabel("Deliver to");
        label.setFont(Theme.SMALL);
        label.setForeground(Theme.SUBTEXT);
        deliverTo.setPlaceholder("No characters");
        deliverTo.setPreferredSize(new Dimension(170, 34));
        who.add(label, BorderLayout.WEST);
        who.add(deliverTo, BorderLayout.CENTER);
        JPanel whoWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        whoWrap.setOpaque(false);
        whoWrap.add(who);
        head.add(whoWrap, BorderLayout.EAST);

        notice.setFont(Theme.SMALL);
        notice.setForeground(new Color(0xe0, 0xa0, 0x3a));
        notice.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        head.add(notice, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        NewsPanel.styleScrollBar(scroll.getVerticalScrollBar());
        add(scroll, BorderLayout.CENTER);

        // Item pictures appear once the game client has drawn them: look again now and then.
        new Timer(15_000, e -> loadPictures()).start();
    }

    void setProfiles(List<Backend.Profile> profiles) {
        this.profiles = List.copyOf(profiles);
        Backend.Profile had = deliverTo.getSelected();
        deliverTo.setItems(profiles);
        if (had != null) {
            for (Backend.Profile p : profiles) {
                if (p.accountId() == had.accountId()) {
                    deliverTo.setSelected(p);
                }
            }
        }
    }

    /** Fetches the products (once per sign-in; again after a failure). */
    void load() {
        if (store != null || loading || !backend.hasSession()) {
            return;
        }
        loading = true;
        notice.setText("Loading the store...");
        new SwingWorker<Backend.Store, Void>() {
            @Override
            protected Backend.Store doInBackground() throws Exception {
                return backend.store();
            }

            @Override
            protected void done() {
                loading = false;
                try {
                    show(get());
                } catch (Exception e) {
                    notice.setText("Couldn't load the store. Open this tab again to retry.");
                }
            }
        }.execute();
    }

    /** Signing out forgets the products (another account may see other options). */
    void reset() {
        store = null;
        cards.clear();
        content.removeAll();
        content.revalidate();
        content.repaint();
    }

    private void show(Backend.Store fetched) {
        store = fetched;
        notice.setText(
                fetched.payments() ? " "
                        : fetched.testPurchases() ? "Test mode: purchases are free and staff-only until payments are switched on."
                                : "The store opens soon. Here's a preview of what's coming.");
        rebuild();

        java.util.Map<Integer, Integer> wanted = new java.util.LinkedHashMap<>();
        for (Backend.StoreItem item : fetched.items()) {
            wanted.putIfAbsent(item.isPoints() ? POINTS_PICTURE : item.objId(), item.isPoints() ? -1 : item.useAnim());
        }
        java.util.Map<Integer, String> recipes = new java.util.HashMap<>();
        for (Backend.StoreItem item : fetched.items()) {
            if (!item.render().isEmpty()) {
                recipes.put(item.objId(), item.render());
            }
        }
        Renders.want(wanted, recipes);
        if (fetched.admin()) {
            // This machine's pictures become everyone's default.
            Renders.publish(new ArrayList<>(wanted.keySet()));
        }
        loadPictures();
    }

    /** The tabs, and the open one's products. */
    private void rebuild() {
        content.removeAll();
        cards.clear();
        List<String> categories = new ArrayList<>(CATEGORY_ORDER);
        for (Backend.StoreItem item : store.items()) {
            if (!categories.contains(item.category())) {
                categories.add(item.category());
            }
        }
        categories.removeIf(c -> store.items().stream().noneMatch(i -> i.category().equals(c)));
        categories.add(0, ALL);
        if (!categories.contains(category)) {
            category = ALL;
        }
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabs.setOpaque(false);
        tabs.setAlignmentX(LEFT_ALIGNMENT);
        tabs.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        for (String c : categories) {
            tabs.add(new Tab(c, c.equals(category), () -> {
                category = c;
                rebuild();
                loadPictures();
            }));
            tabs.add(Box.createHorizontalStrut(8));
        }
        content.add(tabs);
        if (category.equals(ALL)) {
            for (String c : categories.subList(1, categories.size())) {
                section(c, store.items().stream().filter(i -> i.category().equals(c)).toList(), 4);
            }
        } else {
            section(null, store.items().stream().filter(i -> i.category().equals(category)).toList(), 4);
        }
        content.revalidate();
        content.repaint();
    }

    /** A category tab: a rounded pill, gold while open. */
    static final class Tab extends JComponent {
        private final String text;
        private final boolean open;
        private boolean hover;

        Tab(String text, boolean open, Runnable onClick) {
            this.text = text;
            this.open = open;
            setFont(new Font("SansSerif", Font.BOLD, 13));
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            setPreferredSize(new Dimension(fm.stringWidth(text) + 32, 30));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    onClick.run();
                }

                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(open ? new Color(0x3a, 0x2c, 0x18) : hover ? new Color(0x34, 0x30, 0x2c) : new Color(0x2a, 0x27, 0x24));
            g.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g.setColor(open ? Theme.GOLD : Theme.BORDER);
            g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            g.setFont(getFont());
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.setColor(open ? new Color(0xff, 0xcf, 0x73) : hover ? Theme.TEXT : Theme.SUBTEXT);
            g.drawString(text, (w - fm.stringWidth(text)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    private void section(String name, List<Backend.StoreItem> items, int columns) {
        if (items.isEmpty()) {
            return;
        }
        if (name != null) {
            addTitle(name);
        }
        JPanel grid = new JPanel(new GridLayout(0, columns, 12, 12));
        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 10));
        grid.setAlignmentX(LEFT_ALIGNMENT);
        for (Backend.StoreItem item : items) {
            ItemCard card = new ItemCard(item);
            cards.add(card);
            grid.add(card);
        }
        content.add(grid);
    }

    private void addTitle(String name) {
        JLabel title = new JLabel(name);
        title.setFont(SECTION);
        title.setForeground(Theme.TEXT);
        title.setBorder(BorderFactory.createEmptyBorder(8, 0, 10, 0));
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        content.add(titleRow);
    }

    private void loadPictures() {
        if (cards.isEmpty()) {
            return;
        }
        new SwingWorker<List<Renders.Set>, Void>() {
            @Override
            protected List<Renders.Set> doInBackground() {
                List<Renders.Set> sets = new ArrayList<>();
                for (ItemCard card : cards) {
                    sets.add(card.hasPicture() ? null : Renders.itemOrDefault(card.pictureId()));
                }
                return sets;
            }

            @Override
            protected void done() {
                try {
                    List<Renders.Set> sets = get();
                    for (int i = 0; i < sets.size() && i < cards.size(); i++) {
                        ItemCard card = cards.get(i);
                        if (sets.get(i) != null) {
                            Renders.load(sets.get(i), ItemCard.PICTURE, card::setFrames);
                        }
                    }
                } catch (Exception ignored) {
                    // cosmetic
                }
            }
        }.execute();
    }

    /** The product up close, with Buy now (ItemDetail). */
    private void open(Backend.StoreItem item, int pictureId) {
        ItemDetail.open(this, item, pictureId, store, profiles, deliverTo.getSelected(), (it, who) -> {
            if (who != null) {
                deliverTo.setSelected(who);
            }
            buy(it, who);
        });
    }

    private void buy(Backend.StoreItem item, Backend.Profile profile) {
        if (profile == null) {
            Dialogs.message(this, "Store", "Choose which character it is for first.");
            return;
        }
        String who = Renders.clean(profile.displayName());
        String body = store.payments()
                ? "Buy " + item.name() + " for " + who + " (" + item.price() + ")? Payment opens in your browser."
                : "Test purchase: " + item.name() + " for " + who + ", free (staff only).";
        if (!Dialogs.confirm(this, "Store", body, store.payments() ? "Buy" : "Give it", false)) {
            return;
        }
        new SwingWorker<Backend.Purchase, Void>() {
            @Override
            protected Backend.Purchase doInBackground() throws Exception {
                return backend.buy(item, profile);
            }

            @Override
            protected void done() {
                try {
                    Backend.Purchase purchase = get();
                    if (purchase.checkoutUrl() != null && Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create(purchase.checkoutUrl()));
                    }
                    if (purchase.sessionId() != null) {
                        follow(purchase.sessionId(), item, profile);
                    } else {
                        Dialogs.message(StorePanel.this, "Store", purchase.message());
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    Dialogs.message(StorePanel.this, "Store", "That didn't work: " + cause.getMessage());
                }
            }
        }.execute();
    }

    /** The purchase being followed (its status line at the top), and how long it is followed. */
    private Timer following;
    private static final long FOLLOW_MS = 30 * 60_000L;

    /**
     * Follows a purchase until it is delivered: "waiting for payment" while the checkout page is open,
     * then "payment received" (Stripe's callback reached the server), then "delivered" (the
     * character was online and got it, or logged in since).
     */
    private void follow(String sessionId, Backend.StoreItem item, Backend.Profile profile) {
        if (following != null) {
            following.stop();
        }
        String who = Renders.clean(profile.displayName());
        long until = System.currentTimeMillis() + FOLLOW_MS;
        String[] last = {""};
        setStatus("Waiting for your payment for " + item.name() + "... (finish it in your browser)", new Color(0xe0, 0xa0, 0x3a));
        following = new Timer(3000, null);
        following.addActionListener(e -> {
            if (System.currentTimeMillis() > until) {
                following.stop();
                setStatus("No payment seen for " + item.name() + ". If you paid, it arrives at " + who + "'s next login.", Theme.SUBTEXT);
                return;
            }
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    return backend.purchaseStatus(sessionId);
                }

                @Override
                protected void done() {
                    String status;
                    try {
                        status = get();
                    } catch (Exception ex) {
                        return; // try again on the next tick
                    }
                    if (status.equals(last[0])) {
                        return;
                    }
                    last[0] = status;
                    switch (status) {
                        case "paid" -> setStatus("Payment received! " + item.name() + " goes to " + who
                                + " (straight away if they're in game, else at their next login).", Theme.GREEN_HOVER);
                        case "claimed" -> {
                            following.stop();
                            setStatus("\u2713 " + item.name() + " delivered to " + who + ". Thank you for your support!", Theme.GREEN_HOVER);
                        }
                        default -> {}
                    }
                }
            }.execute();
        });
        following.start();
    }

    private void setStatus(String text, Color colour) {
        notice.setText(text);
        notice.setForeground(colour);
    }

    /** One product, as in a webshop: its picture (turning under the mouse), name, price and a line; click for more. */
    private final class ItemCard extends JComponent {
        static final int PICTURE = 110;
        private final Backend.StoreItem item;
        private final Spinner picture = new Spinner(false);
        private boolean hovering;

        ItemCard(Backend.StoreItem item) {
            this.item = item;
            setLayout(null);
            setPreferredSize(new Dimension(150, 232));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            add(picture);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    setHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    java.awt.Point p = javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), ItemCard.this);
                    if (!contains(p)) {
                        setHover(false);
                    }
                }

                private java.awt.Point pressedAt;

                @Override
                public void mousePressed(MouseEvent e) {
                    pressedAt = e.getLocationOnScreen();
                }

                /**
                 * Opens on release (a click that moved a pixel or two is still a click; Java's own
                 * mouseClicked is not), unless the picture was dragged round or the mouse left the card.
                 */
                @Override
                public void mouseReleased(MouseEvent e) {
                    java.awt.Point at = e.getLocationOnScreen();
                    java.awt.Point p = javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), ItemCard.this);
                    boolean still = pressedAt != null && pressedAt.distance(at) <= 6;
                    pressedAt = null;
                    if (still && contains(p) && e.getButton() == MouseEvent.BUTTON1) {
                        open(item, pictureId());
                    }
                }
            };
            addMouseListener(mouse);
            picture.addMouseListener(mouse);
        }

        private void setHover(boolean on) {
            hovering = on;
            picture.setHovering(on);
            repaint();
        }

        int pictureId() {
            return item.isPoints() ? POINTS_PICTURE : item.objId();
        }

        boolean hasPicture() {
            return picture.hasFrames();
        }

        void setFrames(java.awt.image.BufferedImage[] frames) {
            picture.setFrames(frames);
            repaint();
        }

        @Override
        public void doLayout() {
            picture.setBounds(0, 8, getWidth(), PICTURE + 16);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(0, h),
                    new float[] {0f, 1f}, new Color[] {new Color(0x22, 0x20, 0x26), new Color(0x1c, 0x19, 0x16)}));
            g.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            float r = 70;
            int cy = 8 + (PICTURE + 16) / 2;
            g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, cy), r,
                    new float[] {0f, 1f}, new Color[] {new Color(255, 200, 120, hovering ? 70 : 46), new Color(0, 0, 0, 0)}));
            g.fillOval((int) (w / 2f - r), (int) (cy - r), (int) (2 * r), (int) (2 * r));
            g.setColor(hovering ? new Color(0x8a, 0x6a, 0x38) : new Color(0x33, 0x30, 0x2a));
            g.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
            if (!picture.hasFrames()) {
                g.setFont(Theme.SMALL);
                g.setColor(Theme.SUBTEXT);
                String s = "Picture after your next game";
                g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, cy + 4);
            }
            int ty = 8 + PICTURE + 30;
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(hovering ? new Color(0xff, 0xcf, 0x73) : Theme.TEXT);
            g.drawString(clip(g, item.name(), w - 24), 12, ty);
            SaleTag.price(g, item, 12, ty + 19, new Color(0xff, 0xc8, 0x5a));
            g.setFont(Theme.SMALL);
            g.setColor(Theme.SUBTEXT);
            String line = item.minRank() != null ? item.minRank() + " rank only" : item.description();
            int y = ty + 38;
            for (String row : rows(g, line, w - 24, 2)) {
                g.drawString(row, 12, y);
                y += 14;
            }
            g.setColor(hovering ? new Color(0xe0, 0xa0, 0x3a) : new Color(0x7f, 0x78, 0x6d));
            g.drawString("View \u203a", w - 12 - g.getFontMetrics().stringWidth("View \u203a"), ty);
            g.dispose();
        }

        /** On sale: the ribbon over the corner, over the picture too. */
        @Override
        protected void paintChildren(Graphics graphics) {
            super.paintChildren(graphics);
            if (item.onSale()) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                SaleTag.ribbon(g, getWidth(), getHeight(), 14, item.percentOff());
                g.dispose();
            }
        }

        private String clip(Graphics2D g, String s, int max) {
            var fm = g.getFontMetrics();
            if (fm.stringWidth(s) <= max - 40) return s;
            while (s.length() > 1 && fm.stringWidth(s + "...") > max - 40) s = s.substring(0, s.length() - 1);
            return s + "...";
        }

        /** [text] in at most [max] rows of [width] (the last one cut short with "..."). */
        private List<String> rows(Graphics2D g, String text, int width, int max) {
            var fm = g.getFontMetrics();
            List<String> out = new ArrayList<>();
            StringBuilder row = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = row.length() == 0 ? word : row + " " + word;
                if (fm.stringWidth(next) > width && row.length() > 0) {
                    out.add(row.toString());
                    row = new StringBuilder(word);
                    if (out.size() == max) break;
                } else {
                    row = new StringBuilder(next);
                }
            }
            if (out.size() < max && row.length() > 0) out.add(row.toString());
            if (out.size() == max && fm.stringWidth(text) > fm.stringWidth(String.join(" ", out))) {
                String last = out.get(max - 1);
                while (last.length() > 1 && fm.stringWidth(last + "...") > width) last = last.substring(0, last.length() - 1);
                out.set(max - 1, last + "...");
            }
            return out;
        }
    }
}
