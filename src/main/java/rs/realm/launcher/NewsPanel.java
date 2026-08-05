package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
import javax.swing.ScrollPaneConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The left column: whatever was posted in the announcements channel.
 *
 * A grid of cards rather than a list, because the posts carry screenshots and a screenshot is the
 * point of most of them. Cards render as soon as the text arrives and fill in their image later —
 * see {@link Avatar#loadThumbnail}.
 */
public final class NewsPanel extends JPanel {

    private static final int CARD_WIDTH = 380;
    private static final int IMAGE_HEIGHT = 150;

    /** The newest post gets the full width and a taller image — it is the one people came for. */
    private static final int HERO_WIDTH = 780;

    private static final int HERO_IMAGE_HEIGHT = 240;

    private final JPanel column = new JPanel();
    private final JPanel grid = new JPanel(new GridLayout(0, 2, 14, 14));
    private final JLabel emptyLabel = new JLabel("No updates yet.");

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

        JPanel holder = new JPanel(new BorderLayout());
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
    public void setNews(List<Backend.News> items) {
        column.removeAll();
        grid.removeAll();
        if (items.isEmpty()) {
            showMessage("No updates yet.");
            return;
        }

        JComponent hero = card(items.get(0), HERO_WIDTH, HERO_IMAGE_HEIGHT, 20);
        hero.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, HERO_IMAGE_HEIGHT + 170));
        column.add(hero);

        if (items.size() > 1) {
            column.add(Box.createVerticalStrut(14));
            for (Backend.News item : items.subList(1, items.size())) {
                grid.add(card(item, CARD_WIDTH, IMAGE_HEIGHT, 14));
            }
            grid.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(grid);
        }
        revalidate();
        repaint();
    }

    private JComponent card(Backend.News item, int width, int imageHeight, int titleSize) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));

        JLabel image = new JLabel();
        image.setPreferredSize(new Dimension(width, imageHeight));
        image.setOpaque(true);
        image.setBackground(Theme.TRACK);
        Avatar.loadThumbnail(image, item.imageUrl(), width, imageHeight);
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
        String summary = summarise(item.body(), width == HERO_WIDTH ? 600 : 150);
        if (!summary.isEmpty()) {
            // HTML gives us wrapping inside a fixed width without a JTextArea's scroll/selection
            // behaviour, which is wrong for what is really a paragraph of static text.
            JLabel body =
                    new JLabel("<html><body style='width:" + (width - 40) + "px'>" + escape(summary) + "</body></html>");
            body.setForeground(Theme.SUBTEXT);
            body.setFont(Theme.BODY);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(body);
            text.add(Box.createVerticalStrut(8));
        }

        JLabel date = new JLabel(formatDate(item.timestamp()));
        date.setForeground(Theme.SUBTEXT);
        date.setFont(Theme.SMALL);
        date.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(date);

        card.add(text, BorderLayout.CENTER);
        return card;
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
