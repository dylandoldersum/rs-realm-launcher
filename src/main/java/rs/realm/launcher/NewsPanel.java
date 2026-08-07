package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The left column: whatever was posted in the announcements channel.
 *
 * A grid of cards rather than a list, because the posts carry screenshots and a screenshot is the
 * point of most of them. Cards render as soon as the text arrives and fill in their image later —
 * see {@link Avatar#loadThumbnail}.
 */
public final class NewsPanel extends JPanel {

    /**
     * Both image heights are sized so the whole feed fits the window without scrolling.
     *
     * <p>Measured rather than guessed: at the default 1180x700 the viewport is 570 pixels and the
     * old 240/150 pair needed 580, which is why a scrollbar appeared for the sake of ten pixels.
     * They shrink together so the newest post keeps its head above the other two — dropping the
     * hero alone until it fit would have left it barely taller than the cards below it.
     */
    private static final int IMAGE_HEIGHT = 120;

    /** The newest post gets the full width and a taller image — it is the one people came for. */
    private static final int HERO_IMAGE_HEIGHT = 180;

    /** One hero plus a 2x2 grid under it. */
    private static final int PER_PAGE = 5;

    private final JPanel column = new JPanel();
    private final JPanel grid = new JPanel(new GridLayout(0, 2, 14, 14));
    private final JLabel emptyLabel = new JLabel("No updates yet.");

    private List<Backend.News> items = List.of();
    private int page;

    private final JPanel footer = new JPanel(new BorderLayout());
    private final JLabel pageLabel = new JLabel("", SwingConstants.CENTER);
    private final PageArrow previous = new PageArrow("< Newer", () -> turnTo(page - 1));
    private final PageArrow next = new PageArrow("Older >", () -> turnTo(page + 1));

    public NewsPanel() {
        super(new BorderLayout());
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 12));

        JLabel heading = new JLabel("Recent Updates");
        heading.setForeground(Theme.TEXT);
        heading.setFont(new Font("SansSerif", Font.BOLD, 18));
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        add(heading, BorderLayout.NORTH);

        grid.setOpaque(false);
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        emptyLabel.setForeground(Theme.SUBTEXT);
        emptyLabel.setFont(Theme.BODY);

        // Tracks the viewport's width instead of asking for its children's preferred width. That is
        // the whole fix for the column that hung off the right edge: without it a scroll pane hands
        // the content whatever width it wants and clips the overflow, so a card asking for 380px
        // twice over simply did not fit and the second one was cut in half.
        JPanel holder =
                new JPanel(new BorderLayout()) {
                    @Override
                    public Dimension getPreferredSize() {
                        Dimension preferred = super.getPreferredSize();
                        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
                        if (viewport != null) {
                            preferred.width = viewport.getWidth();
                        }
                        return preferred;
                    }
                };
        holder.setOpaque(false);
        holder.add(column, BorderLayout.NORTH);

        JScrollPane scroll =
                new JScrollPane(
                        holder,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        styleScrollBar(scroll.getVerticalScrollBar());
        add(scroll, BorderLayout.CENTER);

        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 12));
        pageLabel.setForeground(Theme.SUBTEXT);
        pageLabel.setFont(Theme.SMALL);
        footer.add(previous, BorderLayout.WEST);
        footer.add(pageLabel, BorderLayout.CENTER);
        footer.add(next, BorderLayout.EAST);
        // Only appears once there is a second page — a pager over a single page is furniture.
        footer.setVisible(false);
        add(footer, BorderLayout.SOUTH);
    }

    /** Shows a "still loading" line; replaced by {@link #setNews} or {@link #setError}. */
    public void setLoading() {
        showMessage("Loading updates...");
    }

    public void setError(String message) {
        showMessage(message);
    }

    private void showMessage(String message) {
        column.removeAll();
        grid.removeAll();
        emptyLabel.setText(message);
        emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(emptyLabel);
        revalidate();
        repaint();
    }

    /**
     * The newest post across the full width, the rest in two columns underneath.
     *
     * An update feed is not a list of equals — the top one is what the player opened the launcher
     * to read, and the older ones are there to be skimmed. Giving the first a hero card and a bigger
     * image says that without needing a label.
     */
    public void setNews(List<Backend.News> fetched) {
        // Somebody who has paged back through the archive stays where they are; only a reader on
        // the first page is moved, and there the move IS the point — that is where a new post lands.
        boolean onFirstPage = page == 0;
        this.items = List.copyOf(fetched);
        this.page = onFirstPage ? 0 : Math.min(page, Math.max(0, pageCount() - 1));
        render();
    }

    private int pageCount() {
        return (int) Math.ceil(items.size() / (double) PER_PAGE);
    }

    private void turnTo(int target) {
        if (target < 0 || target >= pageCount() || target == page) {
            return;
        }
        page = target;
        render();
    }

    private void render() {
        column.removeAll();
        grid.removeAll();
        if (items.isEmpty()) {
            footer.setVisible(false);
            showMessage("No updates yet.");
            return;
        }

        int from = page * PER_PAGE;
        List<Backend.News> shown = items.subList(from, Math.min(from + PER_PAGE, items.size()));

        JComponent hero = card(shown.get(0), true, HERO_IMAGE_HEIGHT, 20);
        hero.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, HERO_IMAGE_HEIGHT + 120));
        column.add(hero);

        if (shown.size() > 1) {
            column.add(Box.createVerticalStrut(14));
            for (Backend.News item : shown.subList(1, shown.size())) {
                grid.add(card(item, false, IMAGE_HEIGHT, 14));
            }
            grid.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Rows of two, each row as tall as its image plus the text under it. Without a cap the
            // BoxLayout hands the grid every spare pixel of height and the cards stretch.
            int rows = (int) Math.ceil((shown.size() - 1) / 2.0);
            grid.setMaximumSize(
                    new Dimension(Integer.MAX_VALUE, rows * (IMAGE_HEIGHT + 110) + (rows - 1) * 14));
            column.add(grid);
        }

        int pages = pageCount();
        footer.setVisible(pages > 1);
        pageLabel.setText(pages > 1 ? (page + 1) + " / " + pages : "");
        previous.setEnabled(page > 0);
        next.setEnabled(page < pages - 1);

        revalidate();
        repaint();
    }

    /**
     * A page arrow.
     *
     * Worded by direction through time rather than "previous/next", because on a feed sorted newest
     * first those two words point whichever way the reader assumes. Dimmed and unclickable at
     * either end instead of hidden, so the pager does not change shape as you move through it.
     */
    private static final class PageArrow extends JLabel {

        private final Runnable action;
        private boolean enabled = true;

        PageArrow(String text, Runnable action) {
            super(text);
            this.action = action;
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setForeground(Theme.SUBTEXT);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mousePressed(MouseEvent e) {
                            if (enabled) {
                                action.run();
                            }
                        }

                        @Override
                        public void mouseEntered(MouseEvent e) {
                            if (enabled) {
                                setForeground(Theme.TEXT);
                            }
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            setForeground(enabled ? Theme.SUBTEXT : Theme.BORDER);
                        }
                    });
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            super.setEnabled(enabled);
            setForeground(enabled ? Theme.SUBTEXT : Theme.BORDER);
            setCursor(
                    Cursor.getPredefinedCursor(
                            enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }
    }

    private JComponent card(Backend.News item, boolean hero, int imageHeight, int titleSize) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));

        CoverImage image = new CoverImage(imageHeight);
        Avatar.load(item.imageUrl(), image::setImage);
        card.add(image, BorderLayout.NORTH);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));

        JLabel title = new JLabel(item.title());
        title.setForeground(Theme.TEXT);
        title.setFont(new Font("SansSerif", Font.BOLD, titleSize));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        text.add(Box.createVerticalStrut(6));

        // The hero has room to say more; the grid cards stay teasers so two of them line up.
        String summary = summarise(item.body(), hero ? 600 : 150);
        if (!summary.isEmpty()) {
            // One line, ellipsised by the label itself if the card is narrow. HTML wrapping was
            // tempting here and is a trap: a JLabel with an <html> body reports the width it would
            // LIKE as its preferred size, which pushes the whole grid wider than the window instead
            // of wrapping inside it — the exact overflow this layout had.
            JLabel body = new JLabel(summary);
            body.setForeground(Theme.SUBTEXT);
            body.setFont(Theme.BODY);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(body);
            text.add(Box.createVerticalStrut(8));
        }

        // Date left, author right, on one line. A BorderLayout rather than a glue-filled box so the
        // author sits against the right edge of the card whatever width the grid gives it.
        JPanel meta = new JPanel(new BorderLayout());
        meta.setOpaque(false);
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel date = new JLabel(formatDate(item.timestamp()));
        date.setForeground(Theme.SUBTEXT);
        date.setFont(Theme.SMALL);
        meta.add(date, BorderLayout.WEST);

        if (!item.author().isBlank()) {
            meta.add(new AuthorTag("Author: ", item.author()), BorderLayout.EAST);
        }
        // The row must not eat the height a BoxLayout would hand it.
        meta.setMaximumSize(new Dimension(Integer.MAX_VALUE, meta.getPreferredSize().height));
        text.add(meta);

        card.add(text, BorderLayout.CENTER);
        return card;
    }

    /**
     * A quiet "Author:" and then the name in gold, glowing.
     *
     * Only the name lights up. The label is scaffolding — it says what the thing next to it is, and
     * giving it the same treatment would spread the emphasis over three words instead of the one
     * that identifies a person.
     *
     * The glow is drawn rather than faked with a shadow: the text is painted a few times at growing
     * offsets with a low alpha, which sums to a soft halo, and then once crisply on top. Swing has
     * no blur, and a real one on a label this small would cost more than it shows.
     */
    private static final class AuthorTag extends JComponent {

        /** Brighter than {@link Theme#GOLD}, which is an amber meant for a button, not for text. */
        private static final Color GOLD = new Color(0xFF, 0xD5, 0x4A);

        private static final Color GLOW = new Color(0xFF, 0xC8, 0x28);

        /** How far the halo reaches. Two rings is a hint of light; three was a smudge. */
        private static final int GLOW_PASSES = 2;

        /** The eight compass directions, as dx/dy pairs — one ring's worth of offsets. */
        private static final int[] RING = {-1, -1, 0, -1, 1, -1, -1, 0, 1, 0, -1, 1, 0, 1, 1, 1};

        private final String label;
        private final String name;
        private final Font labelFont = Theme.SMALL;
        private final Font nameFont = new Font("SansSerif", Font.BOLD, 11);

        AuthorTag(String label, String name) {
            this.label = label;
            this.name = name;
            // Room on the right for the halo, or it is clipped at the card's edge.
            setBorder(BorderFactory.createEmptyBorder(2, 0, 2, GLOW_PASSES));
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int width =
                    getFontMetrics(labelFont).stringWidth(label)
                            + getFontMetrics(nameFont).stringWidth(name);
            int height = Math.max(getFontMetrics(labelFont).getHeight(), getFontMetrics(nameFont).getHeight());
            return new Dimension(
                    width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Insets insets = getInsets();
            int x = insets.left;
            // Both runs sit on one baseline, taken from the taller font so neither is clipped.
            int y = insets.top + Math.max(getFontMetrics(labelFont).getAscent(), getFontMetrics(nameFont).getAscent());

            g2.setFont(labelFont);
            g2.setColor(Theme.SUBTEXT);
            g2.drawString(label, x, y);
            x += g2.getFontMetrics().stringWidth(label);

            g2.setFont(nameFont);
            // A ring of eight offsets per radius rather than a filled square. A square grid piles
            // dozens of passes onto the centre, which reads as a boxy smudge instead of light —
            // and costs ~80 draws where this costs 24.
            for (int radius = GLOW_PASSES; radius >= 1; radius--) {
                // Fainter the further out it reaches.
                g2.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), 30 / radius));
                for (int corner = 0; corner < RING.length; corner += 2) {
                    g2.drawString(name, x + RING[corner] * radius, y + RING[corner + 1] * radius);
                }
            }

            g2.setColor(GOLD);
            g2.drawString(name, x, y);
            g2.dispose();
        }
    }

    /** Trimmed to fit — patch notes run long and a card is a teaser, not the article. */
    private static String summarise(String body, int limit) {
        String trimmed = body.strip();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit - 3).stripTrailing() + "...";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(iso)
                    .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
        } catch (Exception e) {
            // A format we do not recognise is still better shown than swallowed.
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    /** Kept so the panel's background matches the frame when the grid is short. */
    @Override
    public Color getBackground() {
        return Theme.BACKGROUND;
    }

    /**
     * A card's picture, scaled at paint time to whatever width the card ended up with.
     *
     * The scaling has to happen here rather than at load. A card's width comes from the grid, which
     * comes from the window, which the player can resize — so a bitmap sized when the image arrived
     * is right once and wrong afterwards. Asking for a preferred width of zero is what lets the grid
     * decide instead of the picture.
     */
    private static final class CoverImage extends JComponent {
        private BufferedImage source;
        private BufferedImage scaled;

        CoverImage(int height) {
            setPreferredSize(new Dimension(0, height));
            setMinimumSize(new Dimension(0, height));
        }

        void setImage(BufferedImage image) {
            this.source = image;
            this.scaled = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            g.setColor(Theme.TRACK);
            g.fillRect(0, 0, w, h);
            if (source == null || w <= 0 || h <= 0) {
                return;
            }
            // Rescale only when the size actually changed — a repaint on every frame of a resize
            // would otherwise redo the whole bilinear pass each time.
            if (scaled == null || scaled.getWidth() != w || scaled.getHeight() != h) {
                scaled = Avatar.cover(source, w, h);
            }
            g.drawImage(scaled, 0, 0, null);
        }
    }

    /**
     * Strips the scrollbar down to a thumb.
     *
     * Swing's default draws a track, a border and two arrow buttons — three pieces of chrome for
     * something whose only job is to say where you are. Removing them means overriding the UI
     * delegate rather than setting colours: the buttons are components the delegate creates, so the
     * only way to be rid of them is to hand it zero-sized ones.
     */
    private static void styleScrollBar(JScrollBar bar) {
        bar.setUI(
                new BasicScrollBarUI() {
                    @Override
                    protected void configureScrollBarColors() {
                        // One step up from the background, so it reads as a hint rather than a
                        // control until you look for it.
                        thumbColor = Theme.BORDER;
                        trackColor = Theme.BACKGROUND;
                    }

                    @Override
                    protected JButton createDecreaseButton(int orientation) {
                        return zeroSized();
                    }

                    @Override
                    protected JButton createIncreaseButton(int orientation) {
                        return zeroSized();
                    }

                    @Override
                    protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
                        g.setColor(Theme.BACKGROUND);
                        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                    }

                    @Override
                    protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
                        if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                            return;
                        }
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(
                                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(isThumbRollover() ? Theme.SUBTEXT : Theme.BORDER);
                        int inset = 3;
                        int width = bounds.width - inset * 2;
                        g2.fillRoundRect(
                                bounds.x + inset, bounds.y, Math.max(4, width), bounds.height, width, width);
                        g2.dispose();
                    }

                    private JButton zeroSized() {
                        JButton button = new JButton();
                        button.setPreferredSize(new Dimension(0, 0));
                        button.setMinimumSize(new Dimension(0, 0));
                        button.setMaximumSize(new Dimension(0, 0));
                        return button;
                    }
                });
        bar.setPreferredSize(new Dimension(10, 0));
        bar.setUnitIncrement(18);
        bar.setOpaque(false);
    }
}
