package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

/**
 * The left column: whatever was posted in the announcements channel.
 *
 * A grid of cards rather than a list, because the posts carry screenshots and a screenshot is the
 * point of most of them. Cards render as soon as the text arrives and fill in their image later —
 * see {@link Avatar#loadThumbnail}.
 */
public final class NewsPanel extends JPanel {

    private static final int CARD_WIDTH = 260;
    private static final int IMAGE_HEIGHT = 130;

    private final JPanel grid = new JPanel(new GridLayout(0, 3, 14, 14));
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
        emptyLabel.setForeground(Theme.SUBTEXT);
        emptyLabel.setFont(Theme.BODY);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(grid, BorderLayout.NORTH);

        JScrollPane scroll =
                new JScrollPane(
                        holder,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
    }

    /** Shows a "still loading" line; replaced by {@link #setNews} or {@link #setError}. */
    public void setLoading() {
        grid.removeAll();
        emptyLabel.setText("Loading updates...");
        grid.add(emptyLabel);
        revalidate();
        repaint();
    }

    public void setError(String message) {
        grid.removeAll();
        emptyLabel.setText(message);
        grid.add(emptyLabel);
        revalidate();
        repaint();
    }

    public void setNews(List<Backend.News> items) {
        grid.removeAll();
        if (items.isEmpty()) {
            emptyLabel.setText("No updates yet.");
            grid.add(emptyLabel);
        } else {
            for (Backend.News item : items) {
                grid.add(card(item));
            }
        }
        revalidate();
        repaint();
    }

    private JComponent card(Backend.News item) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));

        JLabel image = new JLabel();
        image.setPreferredSize(new Dimension(CARD_WIDTH, IMAGE_HEIGHT));
        image.setOpaque(true);
        image.setBackground(Theme.TRACK);
        Avatar.loadThumbnail(image, item.imageUrl(), CARD_WIDTH, IMAGE_HEIGHT);
        card.add(image, BorderLayout.NORTH);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));

        JLabel title = new JLabel(item.title());
        title.setForeground(Theme.TEXT);
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        text.add(Box.createVerticalStrut(6));

        String summary = summarise(item.body());
        if (!summary.isEmpty()) {
            // HTML gives us wrapping inside a fixed width without a JTextArea's scroll/selection
            // behaviour, which is wrong for what is really a paragraph of static text.
            JLabel body =
                    new JLabel("<html><body style='width:" + (CARD_WIDTH - 30) + "px'>" + escape(summary) + "</body></html>");
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

    /** First few lines only — a card is a teaser, and patch notes can run long. */
    private static String summarise(String body) {
        String trimmed = body.strip();
        if (trimmed.length() <= 180) {
            return trimmed;
        }
        return trimmed.substring(0, 177).stripTrailing() + "...";
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
}
