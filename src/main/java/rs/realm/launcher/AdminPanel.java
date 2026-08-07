package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

/**
 * The Admin tab: who has signed up, who is online, and the handful of things you can do to them.
 *
 * This panel renders and reports intent — it opens no dialogs and calls no endpoints. What an action
 * means (which prompts to show, what to send, how to report the outcome) lives in the frame, through
 * {@link Actions}, so the two are not tangled: this file decides what an admin can see, and the
 * frame decides what happens when they pick something.
 *
 * The tab is only ever added to the window when the server hands over the data, so this class never
 * has to think about permission. See {@link Backend#adminOverview()}.
 */
public final class AdminPanel extends JPanel {

    /** What the panel asks the frame to do. Every one of these ends in a prompt, never a bare click. */
    public interface Actions {

        /** {@code null} means every player online — the bulk give. */
        void giveItem(String targetOrEveryone);

        void sendHome(String player);

        void setDonator(String player);

        void broadcast();

        void refresh();
    }

    private final Actions actions;
    private final JPanel list = new JPanel();
    private final JPanel stats = new JPanel(new GridLayout(1, 4, 12, 0));

    public AdminPanel(Actions actions) {
        this.actions = actions;
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

        add(buildActionBar(), BorderLayout.SOUTH);
    }

    /** The world-wide actions, which belong to nobody in particular and so sit outside the list. */
    private JComponent buildActionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        bar.add(new ActionButton("Give every player an item...", () -> actions.giveItem(null)));
        bar.add(new ActionButton("Broadcast...", actions::broadcast));
        bar.add(new ActionButton("Refresh", actions::refresh));
        return bar;
    }

    /** Replace everything on screen with a fresh overview. */
    public void setData(Backend.AdminOverview overview, List<Backend.OnlinePlayer> online) {
        stats.removeAll();
        stats.add(stat("Online now", String.valueOf(online.size())));
        stats.add(stat("Discord users", String.valueOf(overview.discordUsers())));
        stats.add(stat("Characters", String.valueOf(overview.profiles())));
        stats.add(stat("Never played", String.valueOf(overview.profilesNeverPlayed())));

        list.removeAll();

        list.add(heading("Online now"));
        if (online.isEmpty()) {
            list.add(message("Nobody is online."));
        } else {
            for (Backend.OnlinePlayer player : online) {
                list.add(onlineRow(player));
                list.add(Box.createVerticalStrut(6));
            }
        }

        list.add(Box.createVerticalStrut(18));
        list.add(heading("Discord accounts"));
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

    private JLabel heading(String text) {
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(new Font("SansSerif", Font.BOLD, 11));
        label.setForeground(Theme.SUBTEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        return label;
    }

    /** One online player: name, rank, where they are, and the three things you can do to them. */
    private JPanel onlineRow(Backend.OnlinePlayer player) {
        JPanel card = new JPanel(new BorderLayout(14, 0));
        card.setBackground(Theme.PANEL);
        card.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.BORDER, 1),
                        BorderFactory.createEmptyBorder(8, 14, 8, 10)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel who = new JPanel();
        who.setOpaque(false);
        who.setLayout(new BoxLayout(who, BoxLayout.Y_AXIS));

        JLabel name = new JLabel(player.name());
        name.setFont(new Font("SansSerif", Font.BOLD, 14));
        name.setForeground(Theme.TEXT);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        who.add(name);

        String rank = player.rank();
        JLabel where =
                new JLabel(
                        (rank.isEmpty() ? "" : rank + "  ·  ")
                                + player.x()
                                + ", "
                                + player.z()
                                + (player.level() == 0 ? "" : "  (plane " + player.level() + ")"));
        where.setFont(Theme.SMALL);
        where.setForeground(Theme.SUBTEXT);
        where.setAlignmentX(Component.LEFT_ALIGNMENT);
        who.add(where);
        card.add(who, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(new ActionButton("Give item", () -> actions.giveItem(player.name())));
        buttons.add(new ActionButton("Send home", () -> actions.sendHome(player.name())));
        buttons.add(new ActionButton("Donator", () -> actions.setDonator(player.name())));
        card.add(buttons, BorderLayout.EAST);

        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
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
        JLabel label = new JLabel(text);
        label.setFont(Theme.BODY);
        label.setForeground(Theme.SUBTEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 0));
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

    /** A small bordered button. Flat, so a row of them does not shout louder than the data. */
    private static final class ActionButton extends JLabel {

        ActionButton(String text, Runnable action) {
            super(text, SwingConstants.CENTER);
            setFont(new Font("SansSerif", Font.PLAIN, 11));
            setForeground(Theme.SUBTEXT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Theme.BORDER, 1),
                            BorderFactory.createEmptyBorder(5, 10, 5, 10)));
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mousePressed(MouseEvent e) {
                            action.run();
                        }

                        @Override
                        public void mouseEntered(MouseEvent e) {
                            setForeground(Theme.TEXT);
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            setForeground(Theme.SUBTEXT);
                        }
                    });
        }
    }
}
