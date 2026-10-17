package rs.realm.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * The Home tab: the player's characters, each turning by its own fire ({@link HeroCard}). Only a
 * showcase: who to play is chosen in the game's character lobby, which Play opens. Below them the
 * featured news ({@link FeaturedBanner}).
 */
final class HomePanel extends JPanel {
    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 19);

    private final JPanel row = new JPanel();
    private final JLabel empty = new JLabel("Sign in to see your characters.");
    private final List<HeroCard> cards = new ArrayList<>();
    private final FeaturedBanner banner;
    /** The featured update's picture shown now (null: the built-in one). */
    private String featuredUrl;
    private Map<String, Renders.Set> sets = Map.of();

    HomePanel(Runnable openNews) {
        super(new BorderLayout());
        banner = new FeaturedBanner("/featured-zombies.png", openNews);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Your characters");
        title.setFont(TITLE);
        title.setForeground(new Color(0xf1, 0xea, 0xd9));
        JLabel hint = new JLabel("Drag them round · choose who to play by the campfire in game");
        hint.setFont(Theme.SMALL);
        hint.setForeground(Theme.SUBTEXT);
        top.add(title);
        top.add(Box.createVerticalStrut(3));
        top.add(hint);
        top.add(Box.createVerticalStrut(14));

        row.setOpaque(false);
        row.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        empty.setFont(Theme.BODY);
        empty.setForeground(Theme.SUBTEXT);
        row.add(empty);
        JScrollPane scroll = new JScrollPane(row, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(0, HeroCard.HEIGHT + 2));
        // More characters than fit: the wheel scrolls the row sideways.
        scroll.addMouseWheelListener(e -> {
            var bar = scroll.getHorizontalScrollBar();
            bar.setValue(bar.getValue() + e.getWheelRotation() * 60);
        });

        JPanel middle = new JPanel(new BorderLayout());
        middle.setOpaque(false);
        middle.add(top, BorderLayout.NORTH);
        middle.add(scroll, BorderLayout.CENTER);

        add(middle, BorderLayout.NORTH);

        // Below the heroes: what's new, the picture opening the News tab.
        JPanel featured = new JPanel(new BorderLayout());
        featured.setOpaque(false);
        featured.setBorder(BorderFactory.createEmptyBorder(22, 0, 0, 0));
        JLabel featuredTitle = new JLabel("Featured news");
        featuredTitle.setFont(TITLE);
        featuredTitle.setForeground(new Color(0xf1, 0xea, 0xd9));
        featuredTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        featured.add(featuredTitle, BorderLayout.NORTH);
        featured.add(banner, BorderLayout.CENTER);
        add(featured, BorderLayout.CENTER);

        // New renders appear after a session in game: look again now and then.
        new Timer(15_000, e -> refreshRenders()).start();
    }

    /** The featured update (from the admin website), or null: the built-in banner. */
    void setFeatured(Backend.News featured) {
        String url = featured == null ? null : featured.imageUrl();
        banner.setToolTipText(featured == null ? null : featured.title());
        if (java.util.Objects.equals(url, featuredUrl)) {
            return;
        }
        featuredUrl = url;
        if (url == null) {
            banner.setPicture(null);
        } else {
            Avatar.load(url, picture -> {
                if (url.equals(featuredUrl)) {
                    banner.setPicture(picture);
                }
            });
        }
    }

    /** The signed-in player's characters (empty when signed out). */
    void setProfiles(List<Backend.Profile> profiles) {
        row.removeAll();
        cards.clear();
        if (profiles.isEmpty()) {
            row.add(empty);
        }
        for (Backend.Profile profile : profiles) {
            HeroCard card = new HeroCard(profile);
            cards.add(card);
            if (row.getComponentCount() > 0) {
                row.add(Box.createHorizontalStrut(14));
            }
            row.add(card);
        }
        row.revalidate();
        row.repaint();
        sets = Map.of();
        refreshRenders();
    }

    private void refreshRenders() {
        if (cards.isEmpty()) {
            return;
        }
        new SwingWorker<Map<String, Renders.Set>, Void>() {
            private Renders.Set shape;

            @Override
            protected Map<String, Renders.Set> doInBackground() {
                shape = Renders.anyCharacterOrDefault();
                return Renders.characters();
            }

            @Override
            protected void done() {
                Map<String, Renders.Set> found;
                try {
                    found = get();
                } catch (Exception e) {
                    return;
                }
                Map<String, Renders.Set> previous = sets;
                sets = found;
                // A shape for the silhouettes even when nobody here has played yet (the server's default).
                if (shape != null && found.isEmpty()) {
                    Renders.load(shape, HeroCard.figureHeight(), frames -> shareSilhouette(frames[0]));
                }
                for (HeroCard card : cards) {
                    Renders.Set set = found.get(card.key());
                    Renders.Set had = previous.get(card.key());
                    if (set != null && (had == null || had.time() != set.time() || !card.hasFrames())) {
                        Renders.load(set, HeroCard.figureHeight(), frames -> {
                            card.setFrames(frames);
                            shareSilhouette(frames[0]);
                        });
                    }
                }
            }
        }.execute();
    }

    /** Characters without a render of their own stand as somebody's shadow. */
    private void shareSilhouette(BufferedImage front) {
        for (HeroCard card : cards) {
            if (!card.hasFrames()) {
                card.setSilhouette(front);
            }
        }
    }
}
