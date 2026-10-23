package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The Trading Post tab: what players sell and want to buy on the in-game Trading Post right now
 * (cheapest first, searchable by item; every offer marked Selling or Buying) and its latest sales,
 * read-only: trading stays in the game, at the Trading Post clerk at home. No sign-in needed. It refreshes itself every half minute while it is open.
 *
 * <p>Item pictures are the OSRS wiki's, as the admin website shows them; one that does not load
 * leaves its place empty.
 */
final class TradingPostPanel extends JPanel {
    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 19);
    private static final Font SECTION = new Font("SansSerif", Font.BOLD, 14);
    private static final Font NAME = new Font("SansSerif", Font.BOLD, 13);
    private static final Font PRICE = new Font("SansSerif", Font.BOLD, 13);
    private static final Color ONLINE = new Color(0x6E, 0xC0, 0x4A);
    /** An offer's kind, in the game's colours: sell orange, buy green. */
    private static final Color SELLING = new Color(0xFF, 0x98, 0x1F);
    private static final Color BUYING = new Color(0x0D, 0xC1, 0x0D);
    private static final int ROW_H = 46;
    private static final int ICON = 32;

    private final Backend backend;
    private final JTextField search = new JTextField();
    private final JPanel offers = new JPanel();
    private final JPanel recent = new JPanel();
    private final JLabel notice = new JLabel(" ");
    private final Timer debounce;
    private final Timer refresh;
    private boolean loading;
    private boolean again;

    private static final Map<String, BufferedImage> ICONS = new HashMap<>();
    private static final Set<String> ASKED = new HashSet<>();

    TradingPostPanel(Backend backend) {
        super(new BorderLayout());
        this.backend = backend;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 26, 0, 16));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 10));
        JPanel words = new JPanel();
        words.setOpaque(false);
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Trading Post");
        title.setFont(TITLE);
        title.setForeground(new Color(0xf1, 0xea, 0xd9));
        JLabel hint = new JLabel("What players are selling and buying right now (a green dot: they are online). To trade, visit the Trading Post clerk at home in game.");
        hint.setFont(Theme.BODY);
        hint.setForeground(Theme.SUBTEXT);
        words.add(title);
        words.add(Box.createVerticalStrut(3));
        words.add(hint);
        head.add(words, BorderLayout.WEST);

        search.setPreferredSize(new Dimension(200, 32));
        search.setFont(Theme.BODY);
        search.setForeground(Theme.TEXT);
        search.setCaretColor(Theme.TEXT);
        search.setBackground(Theme.PANEL);
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        search.setToolTipText("Search for an item");
        JPanel searchWrap = new JPanel(new BorderLayout(8, 0));
        searchWrap.setOpaque(false);
        JLabel searchLabel = new JLabel("Search");
        searchLabel.setFont(Theme.SMALL);
        searchLabel.setForeground(Theme.SUBTEXT);
        searchWrap.add(searchLabel, BorderLayout.WEST);
        searchWrap.add(search, BorderLayout.CENTER);
        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setOpaque(false);
        searchBox.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        searchBox.add(searchWrap, BorderLayout.NORTH);
        head.add(searchBox, BorderLayout.EAST);

        notice.setFont(Theme.SMALL);
        notice.setForeground(new Color(0xe0, 0xa0, 0x3a));
        notice.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        head.add(notice, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        JPanel columns = new JPanel(new GridBagLayout());
        columns.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0.62;
        c.insets = new Insets(0, 0, 0, 12);
        columns.add(column("On offer", offers), c);
        c.gridx = 1;
        c.weightx = 0.38;
        c.insets = new Insets(0, 0, 0, 0);
        columns.add(column("Recent sales", recent), c);
        add(columns, BorderLayout.CENTER);

        debounce = new Timer(350, e -> load());
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        refresh = new Timer(30_000, e -> {
            if (isShowing()) {
                load();
            }
        });
        refresh.start();
    }

    private JComponent column(String heading, JPanel list) {
        JPanel col = new JPanel(new BorderLayout());
        col.setOpaque(false);
        JLabel label = new JLabel(heading);
        label.setFont(SECTION);
        label.setForeground(Theme.TEXT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 0));
        col.add(label, BorderLayout.NORTH);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(list, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(ROW_H / 2);
        NewsPanel.styleScrollBar(scroll.getVerticalScrollBar());
        col.add(scroll, BorderLayout.CENTER);
        return col;
    }

    /** Fetches the offers (for what is typed) and the latest sales. */
    void load() {
        if (loading) {
            again = true;
            return;
        }
        loading = true;
        String query = search.getText().trim();
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                return new Object[] {backend.tradingPostOffers(query), backend.tradingPostRecent()};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                loading = false;
                try {
                    Object[] got = get();
                    showOffers((List<Backend.TradingPostOffer>) got[0], query);
                    showRecent((List<Backend.TradingPostTrade>) got[1]);
                    notice.setText(" ");
                } catch (Exception e) {
                    notice.setText("The Trading Post can't be reached right now. It tries again in a moment.");
                }
                if (again) {
                    again = false;
                    load();
                }
            }
        }.execute();
    }

    private void showOffers(List<Backend.TradingPostOffer> list, String query) {
        offers.removeAll();
        if (list.isEmpty()) {
            offers.add(empty(query.isEmpty() ? "Nothing is on offer right now." : "Nobody is selling or buying \"" + query + "\" right now."));
        }
        long now = System.currentTimeMillis();
        for (Backend.TradingPostOffer o : list) {
            String line = o.buy()
                    ? (o.quantity() > 1 ? fmt(o.quantity()) + " wanted · " : "") + "by " + o.seller()
                    : (o.quantity() > 1 ? fmt(o.quantity()) + " for sale · " : "") + "by " + o.seller();
            String right2 = o.quantity() > 1 ? "each · " + ago(now - o.listedAt()) : ago(now - o.listedAt());
            offers.add(new Row(o.item(), line, o.sellerOnline(), fmt(o.price()) + " gp", right2, o.buy() ? "Buying" : "Selling"));
        }
        offers.revalidate();
        offers.repaint();
    }

    private void showRecent(List<Backend.TradingPostTrade> list) {
        recent.removeAll();
        if (list.isEmpty()) {
            recent.add(empty("No sales yet."));
        }
        long now = System.currentTimeMillis();
        for (Backend.TradingPostTrade t : list) {
            String name = (t.quantity() > 1 ? fmt(t.quantity()) + " x " : "") + t.item();
            String line = t.seller() + " → " + t.buyer();
            recent.add(new Row(name, line, false, fmt((long) t.price() * t.quantity()) + " gp", ago(now - t.tradedAt()), null));
        }
        recent.revalidate();
        recent.repaint();
    }

    private static JComponent empty(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.BODY);
        label.setForeground(Theme.SUBTEXT);
        label.setBorder(BorderFactory.createEmptyBorder(12, 4, 12, 4));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(n);
    }

    private static String ago(long millis) {
        long m = Math.max(0, millis / 60_000);
        if (m < 1) {
            return "just now";
        }
        if (m < 60) {
            return m + " min ago";
        }
        if (m < 48 * 60) {
            return (m / 60) + " h ago";
        }
        return (m / (24 * 60)) + " days ago";
    }

    /** One line: the item's picture, its name and a line under it; on the right a price and a line. */
    private final class Row extends JComponent {
        private final String item;
        private final String line;
        private final boolean online;
        private final String price;
        private final String right;
        /** "Selling" / "Buying" before the name (offers), or null (sales). */
        private final String kind;

        Row(String item, String line, boolean online, String price, String right, String kind) {
            this.kind = kind;
            this.item = item;
            this.line = line;
            this.online = online;
            this.price = price;
            this.right = right;
            setAlignmentX(LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(10, ROW_H));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));
            setToolTipText(item);
            String base = item.replaceFirst("^[0-9,]+ x ", "");
            icon(base);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            g.setColor(Theme.PANEL);
            g.fillRoundRect(0, 2, w - 1, ROW_H - 4, 8, 8);
            g.setColor(Theme.BORDER);
            g.drawRoundRect(0, 2, w - 1, ROW_H - 4, 8, 8);

            BufferedImage image = ICONS.get(item.replaceFirst("^[0-9,]+ x ", ""));
            if (image != null) {
                double scale = Math.min((double) ICON / image.getWidth(), (double) ICON / image.getHeight());
                int iw = (int) Math.round(image.getWidth() * scale);
                int ih = (int) Math.round(image.getHeight() * scale);
                g.drawImage(image, 10 + (ICON - iw) / 2, (ROW_H - ih) / 2, iw, ih, null);
            }

            g.setFont(PRICE);
            FontMetrics pm = g.getFontMetrics();
            int priceW = pm.stringWidth(price);
            g.setFont(Theme.SMALL);
            int rightW = g.getFontMetrics().stringWidth(right);
            int textX = 10 + ICON + 10;
            int textW = Math.max(20, w - textX - Math.max(priceW, rightW) - 24);

            g.setFont(NAME);
            int nameX = textX;
            if (kind != null) {
                g.setColor("Buying".equals(kind) ? BUYING : SELLING);
                g.drawString(kind, nameX, 21);
                nameX += g.getFontMetrics().stringWidth(kind) + 8;
            }
            g.setColor(Theme.TEXT);
            g.drawString(cut(g, item, textW - (nameX - textX)), nameX, 21);
            g.setFont(Theme.SMALL);
            int lx = textX;
            if (online) {
                g.setColor(ONLINE);
                g.fillOval(lx, 28, 7, 7);
                lx += 11;
            }
            g.setColor(Theme.SUBTEXT);
            g.drawString(cut(g, line, textW - (lx - textX)), lx, 35);

            g.setFont(PRICE);
            g.setColor(Theme.GOLD_HOVER);
            g.drawString(price, w - 12 - priceW, 21);
            g.setFont(Theme.SMALL);
            g.setColor(Theme.SUBTEXT);
            g.drawString(right, w - 12 - rightW, 35);
            g.dispose();
        }

        private String cut(Graphics2D g, String text, int max) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(text) <= max) {
                return text;
            }
            String s = text;
            while (!s.isEmpty() && fm.stringWidth(s + "...") > max) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "...";
        }
    }

    /** The wiki's picture of [item], fetched once and shared by every row showing it. */
    private void icon(String item) {
        if (!ASKED.add(item)) {
            return;
        }
        String url = "https://oldschool.runescape.wiki/images/"
                + URLEncoder.encode(item.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20") + ".png";
        Avatar.load(url, image -> {
            ICONS.put(item, image);
            offers.repaint();
            recent.repaint();
        });
    }
}
