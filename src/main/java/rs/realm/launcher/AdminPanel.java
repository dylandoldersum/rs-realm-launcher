package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;

/**
 * The Admin tab: who has signed up, and what they own.
 *
 * Read-only on purpose. This is a window onto the database, not a console — there is no button here
 * that changes anything, so a stolen laptop with a live session leaks a member list and nothing more.
 * Anything destructive belongs behind a deliberate tool, not one click away from the Play button.
 *
 * The tab is only ever added to the window when the server hands over the data, so this class never
 * has to think about permission. See {@link Backend#adminOverview()}.
 */
public final class AdminPanel extends JPanel {

    private final JPanel list = new JPanel();
    private final JPanel stats = new JPanel(new GridLayout(1, 4, 12, 0));

    public AdminPanel() {
        setLayout(new BorderLayout(0, 14));
        setBackground(Theme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        stats.setOpaque(false);
        add(stats, BorderLayout.NORTH);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Theme.BACKGROUND);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    /** Replace everything on screen with a fresh overview. */
    public void setOverview(Backend.AdminOverview overview) {
        stats.removeAll();
        stats.add(stat("Discord users", String.valueOf(overview.discordUsers())));
        stats.add(stat("Characters", String.valueOf(overview.profiles())));
        stats.add(stat("Have played", String.valueOf(overview.profilesPlayed())));
        stats.add(stat("Never played", String.valueOf(overview.profilesNeverPlayed())));

        list.removeAll();
        if (overview.users().isEmpty()) {
            list.add(message("Nobody has linked a character yet."));
        } else {
            for (Backend.AdminUser user : overview.users()) {
                list.add(row(user));
                list.add(Box.createVerticalStrut(8));
            }
        }
        // The list is top-aligned; without this the rows stretch to fill a tall window.
        list.add(Box.createVerticalGlue());

        revalidate();
        repaint();
    }

    public void setError(String text) {
        stats.removeAll();
        list.removeAll();
        list.add(message(text));
        revalidate();
        repaint();
    }

    /** One big number with a caption under it. */
    private JPanel stat(String caption, String value) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.PANEL);
        card.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.BORDER, 1),
                        BorderFactory.createEmptyBorder(12, 14, 12, 14)));

        JLabel number = new JLabel(value);
        number.setFont(new Font("SansSerif", Font.BOLD, 24));
        number.setForeground(Theme.TEXT);
        number.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(caption.toUpperCase());
        label.setFont(Theme.SMALL);
        label.setForeground(Theme.SUBTEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(number);
        card.add(Box.createVerticalStrut(2));
        card.add(label);
        return card;
    }

    /** One Discord account and the characters under it. */
    private JPanel row(Backend.AdminUser user) {
        JPanel card = new JPanel(new BorderLayout(14, 0));
        card.setBackground(Theme.PANEL);
        card.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.BORDER, 1),
                        BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        // A BoxLayout hands out the full preferred height to every row unless it is capped, which
        // turns three cards into three tall bands on a big window.
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        String name = user.username().isBlank() ? user.discordId() : user.username();
        JLabel who = new JLabel(name);
        who.setFont(new Font("SansSerif", Font.BOLD, 14));
        who.setForeground(Theme.TEXT);

        JLabel since = new JLabel("linked " + shortDate(user.linkedAt()));
        since.setFont(Theme.SMALL);
        since.setForeground(Theme.SUBTEXT);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        who.setAlignmentX(Component.LEFT_ALIGNMENT);
        since.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(who);
        left.add(since);
        card.add(left, BorderLayout.WEST);

        card.add(characters(user.profiles()), BorderLayout.CENTER);

        JLabel count = new JLabel(String.valueOf(user.profiles().size()), SwingConstants.RIGHT);
        count.setFont(new Font("SansSerif", Font.BOLD, 16));
        count.setForeground(Theme.SUBTEXT);
        card.add(count, BorderLayout.EAST);
        return card;
    }

    /** The character names, dimmed when that character has never actually logged in. */
    private JPanel characters(List<Backend.AdminProfile> profiles) {
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        if (profiles.isEmpty()) {
            names.add(dim("no characters"));
            return names;
        }
        for (Backend.AdminProfile profile : profiles) {
            JLabel label =
                    new JLabel(
                            profile.neverPlayed()
                                    ? profile.name() + "  (never played)"
                                    : profile.name() + "  ·  " + shortDate(profile.lastLogin()));
            label.setFont(Theme.BODY);
            // Dimmed rather than hidden: a signup that never played is the interesting one.
            label.setForeground(profile.neverPlayed() ? Theme.SUBTEXT : Theme.TEXT);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            names.add(label);
        }
        return names;
    }

    private JLabel dim(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.BODY);
        label.setForeground(Theme.SUBTEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JLabel message(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(Theme.BODY);
        label.setForeground(Theme.SUBTEXT);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(40, 0, 0, 0));
        return label;
    }

    /**
     * Trim a timestamp to the date.
     *
     * The column is whatever the database wrote, so this does not parse it — it cuts at the first
     * space or 'T' and shows the rest. A format this does not recognise falls through unchanged
     * rather than turning into "unknown".
     */
    private static String shortDate(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        int cut = value.indexOf(' ');
        if (cut < 0) {
            cut = value.indexOf('T');
        }
        return cut > 0 ? value.substring(0, cut) : value;
    }
}
