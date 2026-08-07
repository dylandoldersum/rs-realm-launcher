package rs.realm.launcher;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * The launcher window.
 *
 * Two columns, after the Jagex Launcher: what's new on the left, what you do on the right. The right
 * column is the whole product — sign in, pick a character, play — and the left is there so opening
 * the launcher is worth something even when you were not going to play.
 *
 * <h2>One button, several meanings</h2>
 *
 * There are two state machines here that a player experiences as one: are you signed in, and is the
 * client installed and current. Rather than stacking two buttons that are each disabled half the
 * time, the primary button carries whichever question is currently in the way — sign in, then
 * download, then update, then play. {@link #applyState} is the only place that decides.
 */
public final class LauncherFrame extends JFrame {

    private enum Phase {
        SIGNED_OUT,
        CHECKING,
        DOWNLOAD,
        UPDATE,
        PLAY,
        OFFLINE,
        WORKING,
        ERROR
    }

    private final Backend backend = new Backend(Config.API_BASE_URL);
    private final Session session = new Session(Config.SESSION_FILE);

    private Manifest remote;
    private Phase phase = Phase.SIGNED_OUT;
    private Backend.Account account;

    /** What the news panel is currently showing, so an unchanged poll can leave it alone. */
    private List<Backend.News> shownNews;

    /** The running game client, or null. Kept so an update is never offered on top of a live one. */
    private Process runningClient;

    private final NewsPanel newsPanel = new NewsPanel();
    private final AdminPanel adminPanel = new AdminPanel();
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 14));
    private final TabButton newsTab = new TabButton("News", () -> showTab(TAB_NEWS));
    private final TabButton adminTab = new TabButton("Admin", () -> showTab(TAB_ADMIN));
    // Overlay labels: these three sit on the artwork, not on a panel. See Theme.OverlayLabel.
    private final Theme.OverlayLabel statusLabel =
            new Theme.OverlayLabel(" ", SwingConstants.CENTER);
    private final Theme.OverlayLabel versionLabel =
            new Theme.OverlayLabel(" ", SwingConstants.CENTER);
    private final Theme.OverlayLabel playerCountLabel =
            new Theme.OverlayLabel(" ", SwingConstants.CENTER);
    private final JLabel accountName = new JLabel();
    private final JLabel accountAvatar = new JLabel();
    private final ChevronDown accountChevron = new ChevronDown();
    private final JPanel accountChip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final RoundBar progress = new RoundBar();
    private final Theme.AccentButton actionButton = new Theme.AccentButton("Sign in with Discord");
    private final Dropdown<Backend.Profile> characterBox = new Dropdown<>(Backend.Profile::displayName);
    private final JPanel characterRow = new JPanel(new BorderLayout(8, 0));

    public LauncherFrame() {
        setTitle(Config.BRAND + " Launcher");
        setUndecorated(true);
        setSize(1180, 700);
        setMinimumSize(new Dimension(980, 620));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        loadWindowIcon();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        root.add(buildTitleBar(), BorderLayout.NORTH);
        center.setOpaque(false);
        center.add(newsPanel, TAB_NEWS);
        center.add(adminPanel, TAB_ADMIN);
        root.add(center, BorderLayout.CENTER);
        showTab(TAB_NEWS);
        root.add(buildSidePanel(), BorderLayout.EAST);
        setContentPane(root);

        actionButton.addActionListener(e -> onAction());

        pollNews();
        pollPlayerCount();
        pollClientUpdates();
        restoreSession();
    }

    /**
     * The "x people playing" line, refreshed while the launcher sits open.
     *
     * Polled rather than fetched once: this window stays open behind the game, and a number that
     * froze at whatever it was when you opened it is worse than no number — it looks live and is
     * not. A failed poll leaves the last good figure alone instead of blanking the line over one
     * dropped request.
     */
    private void pollPlayerCount() {
        refreshPlayerCount();
        Timer timer = new Timer(PLAYER_COUNT_POLL_MILLIS, e -> refreshPlayerCount());
        timer.setRepeats(true);
        timer.start();
    }

    private void refreshPlayerCount() {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                try {
                    return backend.onlinePlayers();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                Integer count;
                try {
                    count = get();
                } catch (Exception e) {
                    count = null;
                }
                if (count == null) {
                    return;
                }
                playerCountLabel.setText(
                        count == 1
                                ? "There is currently 1 person playing."
                                : "There are currently " + count + " people playing.");
            }
        }.execute();
    }

    // ------------------------------------------------------------------------------ title bar ----

    private JComponent buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.TITLE_BAR);
        bar.setPreferredSize(new Dimension(0, 52));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 8));

        // The crest rather than the word. `logo.png` is a wordmark sized for a header, and at the
        // 30 pixels this bar allows its lettering is a smudge; `icon.png` is the square art the
        // app icon is cut from, which still reads at that size.
        JComponent title = buildTitleMark();
        bar.add(title, BorderLayout.WEST);

        // Hidden until there is more than one tab to switch between. A lone "News" button that does
        // nothing is worse than no button.
        tabs.setOpaque(false);
        tabs.setVisible(false);
        tabs.add(newsTab);
        tabs.add(adminTab);
        bar.add(tabs, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        right.setOpaque(false);
        right.add(buildAccountChip());
        right.add(new WindowControl(false, () -> setState(ICONIFIED)));
        right.add(new WindowControl(true, () -> System.exit(0)));
        bar.add(right, BorderLayout.EAST);

        // Drag the frame by the title bar.
        final Point[] anchor = {null};
        MouseAdapter drag =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        anchor[0] = e.getPoint();
                    }

                    @Override
                    public void mouseDragged(MouseEvent e) {
                        if (anchor[0] != null) {
                            setLocation(getX() + e.getX() - anchor[0].x, getY() + e.getY() - anchor[0].y);
                        }
                    }
                };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        title.addMouseListener(drag);
        title.addMouseMotionListener(drag);
        // The tab strip covers the middle of the bar, and a child swallows the events its parent
        // would otherwise have received — without this, the widest part of the bar stops dragging.
        tabs.addMouseListener(drag);
        tabs.addMouseMotionListener(drag);
        return bar;
    }

    /** Avatar + Discord name, with a menu behind it. Hidden entirely while signed out. */
    private JComponent buildAccountChip() {
        accountChip.setOpaque(false);
        accountChip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        accountChip.setVisible(false);

        accountName.setForeground(Theme.TEXT);
        accountName.setFont(new Font("SansSerif", Font.PLAIN, 13));
        accountChip.add(accountName);
        accountChip.add(accountAvatar);
        // The chevron is what makes the avatar read as a menu rather than a decoration.
        accountChip.add(accountChevron);

        MouseAdapter open =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        new FlatMenu()
                                .add("Link an existing character...", () -> showLinkCode())
                                .add("Play with the password login", () -> playWithPasswordLogin())
                                .addSeparator()
                                .addDestructive("Remove this character...", () -> removeProfile())
                                .add("Sign out", () -> signOut())
                                .show(accountChip);
                    }
                };
        accountChip.addMouseListener(open);
        accountName.addMouseListener(open);
        accountAvatar.addMouseListener(open);
        accountChevron.addMouseListener(open);
        return accountChip;
    }

    // ----------------------------------------------------------------------------- side panel ----

    private JComponent buildSidePanel() {
        // The artwork lives behind the controls rather than beside them: this column is 340px wide
        // and every pixel spent on a picture is one the Play button does not get.
        JPanel side = new ArtPanel("/sidebar-art.jpg");
        side.setBackground(Theme.PANEL);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(340, 0));
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(24, 26, 24, 26)));

        // The wordmark stays here as well as the crest in the title bar. They are different marks
        // doing different jobs: the crest identifies the window at 30 pixels, this one is the brand
        // at the top of the column the player actually looks at.
        JComponent logo = buildLogo();
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(logo);
        side.add(Box.createVerticalStrut(22));

        actionButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        actionButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(actionButton);
        side.add(Box.createVerticalStrut(18));

        side.add(buildCharacterRow());
        side.add(Box.createVerticalStrut(16));

        progress.setVisible(false);
        progress.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(progress);
        side.add(Box.createVerticalStrut(8));

        statusLabel.setFont(Theme.BODY);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        side.add(statusLabel);

        side.add(Box.createVerticalGlue());

        playerCountLabel.setForeground(Theme.GREEN_HOVER);
        playerCountLabel.setFont(Theme.SMALL);
        playerCountLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        playerCountLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        side.add(playerCountLabel);
        side.add(Box.createVerticalStrut(4));

        versionLabel.setFont(Theme.SMALL);
        versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        versionLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        side.add(versionLabel);
        return side;
    }

    /** "Character" + the dropdown + a `+` that creates another profile. */
    private JComponent buildCharacterRow() {
        JPanel wrapper = new JPanel();
        wrapper.setOpaque(false);
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setAlignmentX(Component.CENTER_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));

        JLabel label = new Theme.OverlayLabel("Character", SwingConstants.LEFT);
        label.setFont(Theme.SMALL);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(label);
        wrapper.add(Box.createVerticalStrut(6));

        characterBox.setPlaceholder("No characters yet");
        characterBox.setOnSelect(profile -> rememberSelectedProfile());

        PlusButton add = new PlusButton(this::createProfile);
        add.setToolTipText("Create another character");

        characterRow.setOpaque(false);
        characterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        characterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        characterRow.add(characterBox, BorderLayout.CENTER);
        characterRow.add(add, BorderLayout.EAST);
        wrapper.add(characterRow);

        // Nothing to choose between until we know who you are.
        wrapper.setVisible(false);
        characterWrapper = wrapper;
        return wrapper;
    }

    private JComponent characterWrapper;

    /** The crest in the title bar, with the brand name as the fallback if the art is missing. */
    private JComponent buildTitleMark() {
        try {
            var url = getClass().getResource("/icon.png");
            if (url != null) {
                java.awt.image.BufferedImage img = ImageIO.read(url);
                int h = 30;
                int w = Math.max(1, (int) Math.round(img.getWidth() * (h / (double) img.getHeight())));
                JLabel mark = new JLabel(new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH)));
                mark.setPreferredSize(new Dimension(w, h));
                return mark;
            }
        } catch (Exception ignored) {
            // fall through to the word
        }
        JLabel title = new JLabel(Config.BRAND);
        title.setForeground(Theme.TEXT);
        title.setFont(new Font("SansSerif", Font.BOLD, 15));
        return title;
    }

    /**
     * A panel that paints artwork behind its children, solid along the bottom edge and fading out
     * towards the top.
     *
     * <p>The fade is what makes this usable rather than decorative: the controls sit in the upper
     * half, and a picture at full strength behind them would make the character dropdown and the
     * status line hard to read. Alpha is applied at paint time rather than baked into the file, so
     * the source stays an ordinary opaque jpeg — a pre-faded PNG with an alpha channel would be
     * several times the size for the same result.
     *
     * <p>The scaled-and-faded result is cached per panel size. Without that, every repaint would
     * rescale a 1080x2280 image and rebuild the gradient, which is visible as lag while dragging.
     */
    private static final class ArtPanel extends JPanel {
        /** How far up the fade reaches. Below this the art is untouched; above it it is gone. */
        private static final float FADE_TOP = 0.05f;
        private static final float FADE_BOTTOM = 0.62f;

        /** How strong the art gets at its strongest. */
        private static final float MAX_ALPHA = 0.8f;

        /**
         * A darkened strip along the bottom edge, tall enough to cover the player count and the
         * version line.
         *
         * <p>Those two labels sit over the brightest part of the picture — sunlit snow — and pale
         * text on it does not read at any alpha worth using. Turning the whole image down far
         * enough to fix that leaves nothing worth looking at, so the darkening is confined to the
         * strip where the text actually is.
         */
        private static final int SCRIM_HEIGHT = 120;

        /**
         * All the way to the panel colour at the very edge, so the version line is exactly as
         * legible as it was before there was any artwork. Stopping short of 1 leaves a haze of
         * snow behind the smallest, palest text on the screen.
         */
        private static final float SCRIM_STRENGTH = 1.0f;

        private final java.awt.image.BufferedImage source;
        private java.awt.image.BufferedImage cached;
        private int cachedW = -1;
        private int cachedH = -1;

        ArtPanel(String resource) {
            java.awt.image.BufferedImage img = null;
            try {
                var url = getClass().getResource(resource);
                if (url != null) {
                    img = ImageIO.read(url);
                }
            } catch (Exception ignored) {
                // A missing backdrop is a cosmetic loss, not a reason to fail to start.
            }
            this.source = img;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (source == null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (cached == null || cachedW != w || cachedH != h) {
                cached = render(w, h);
                cachedW = w;
                cachedH = h;
            }
            g.drawImage(cached, 0, 0, null);
        }

        /** Scales the art to cover the panel, anchors it to the bottom, then erases the top. */
        private java.awt.image.BufferedImage render(int w, int h) {
            java.awt.image.BufferedImage out =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Cover, not fit: scale by whichever axis leaves no empty edge, and let the overflow
            // run off the top, which is sky.
            double scale = Math.max(w / (double) source.getWidth(), h / (double) source.getHeight());
            int sw = (int) Math.ceil(source.getWidth() * scale);
            int sh = (int) Math.ceil(source.getHeight() * scale);
            g.drawImage(source, (w - sw) / 2, h - sh, sw, sh, null);

            // DST_IN keeps the destination only where the incoming paint is opaque, so painting a
            // transparent-to-opaque ramp over it erases the top and leaves the bottom untouched.
            g.setComposite(AlphaComposite.DstIn);
            g.setPaint(new LinearGradientPaint(
                    new Point2D.Float(0, h * FADE_TOP),
                    new Point2D.Float(0, h * FADE_BOTTOM),
                    new float[] {0f, 1f},
                    new Color[] {new Color(0f, 0f, 0f, 0f), new Color(0f, 0f, 0f, MAX_ALPHA)}));
            g.fillRect(0, 0, w, h);

            // Back to normal painting to lay the scrim ON TOP of the art, in the panel's own colour
            // so it reads as the background rising rather than as a grey bar.
            g.setComposite(AlphaComposite.SrcOver);
            Color base = getBackground() == null ? Color.BLACK : getBackground();
            int scrimTop = Math.max(0, h - SCRIM_HEIGHT);
            g.setPaint(new LinearGradientPaint(
                    new Point2D.Float(0, scrimTop),
                    new Point2D.Float(0, h),
                    new float[] {0f, 1f},
                    new Color[] {
                        new Color(base.getRed(), base.getGreen(), base.getBlue(), 0),
                        new Color(
                                base.getRed(),
                                base.getGreen(),
                                base.getBlue(),
                                Math.round(255 * SCRIM_STRENGTH))
                    }));
            g.fillRect(0, scrimTop, w, h - scrimTop);
            g.dispose();
            return out;
        }
    }

    private JComponent buildLogo() {
        java.awt.image.BufferedImage img = null;
        try {
            var url = getClass().getResource("/logo.png");
            if (url != null) {
                img = ImageIO.read(url);
            }
        } catch (Exception ignored) {
            // fall through to the drawn badge
        }
        if (img != null) {
            int maxW = 240;
            int maxH = 110;
            double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
            int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            JLabel label = new JLabel(new ImageIcon(scaled));
            label.setPreferredSize(new Dimension(w, h));
            label.setMaximumSize(new Dimension(w, h));
            return label;
        }
        JLabel brand = new JLabel(Config.BRAND, SwingConstants.CENTER);
        brand.setForeground(Theme.TEXT);
        brand.setFont(Theme.H1);
        return brand;
    }

    // --------------------------------------------------------------------------------- signing ----

    /** Reuses a stored session if there is one; otherwise shows the sign-in button. */
    private void restoreSession() {
        String token = session.token();
        if (token == null) {
            applyState(Phase.SIGNED_OUT);
            return;
        }
        backend.setSession(token);
        // Draw what we remembered immediately, so a slow network does not show a signed-out window
        // to someone who is signed in.
        account = new Backend.Account("", session.username(), session.avatarUrl());
        showAccount(account);
        loadProfiles(true);
    }

    private void signIn() {
        applyState(Phase.WORKING);
        statusLabel.setText("Waiting for Discord...");
        new SwingWorker<Backend.Account, Void>() {
            private String failure;

            @Override
            protected Backend.Account doInBackground() {
                try {
                    Backend.OAuthConfig oauth = backend.oauthConfig();
                    if (!oauth.enabled() || oauth.clientId().isBlank()) {
                        failure = "Discord login is not configured on the server yet.";
                        return null;
                    }
                    DiscordLogin.Result login =
                            DiscordLogin.awaitCode(oauth.clientId(), oauth.redirectUri());
                    return backend.signIn(login);
                } catch (Exception e) {
                    failure = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                Backend.Account signedIn = null;
                try {
                    signedIn = get();
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                if (signedIn == null) {
                    statusLabel.setText(failure == null ? "Sign-in failed." : failure);
                    applyState(Phase.SIGNED_OUT);
                    return;
                }
                account = signedIn;
                session.save(backend.session(), signedIn);
                showAccount(signedIn);
                loadProfiles(false);
            }
        }.execute();
    }

    /**
     * Fetches a link code and shows it, for characters that existed before Discord did.
     *
     * The code is deliberately not applied here — the launcher cannot prove the player owns an old
     * character, only that they own this Discord. Typing it in game supplies the other half, because
     * being logged in on a character is proof the server can see and this window cannot.
     */
    private void showLinkCode() {
        new SwingWorker<String, Void>() {
            private String failure;

            @Override
            protected String doInBackground() {
                try {
                    return backend.linkCode();
                } catch (Exception e) {
                    failure = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                String code = null;
                try {
                    code = get();
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                if (code == null) {
                    Dialogs.message(
                            LauncherFrame.this,
                            "Link an existing character",
                            failure == null ? "Couldn't get a code." : failure);
                    return;
                }
                Dialogs.message(
                        LauncherFrame.this,
                        "Link an existing character",
                        "Log in on the character you want to link, then type:\n\n"
                                + "::discord "
                                + code
                                + "\n\nThe code works once and expires in 10 minutes.");
            }
        }.execute();
    }

    /**
     * Starts the client with no launch token, which brings back the username/password screen.
     *
     * The escape hatch for an account that has not been linked yet — and, less obviously, for any
     * time the Discord side is the thing that is broken. Handing a token puts the gamepack into
     * account mode, where there is no password field at all, so without this a player whose profile
     * will not load has no way in.
     */
    private void playWithPasswordLogin() {
        try {
            GameClient.play();
            statusLabel.setText("Started with the password login.");
        } catch (Exception e) {
            statusLabel.setText("Couldn't launch: " + e.getMessage());
        }
    }

    /**
     * Brings the launcher back to the front once the client goes away.
     *
     * The client closes itself when a launcher-started session is logged out, and by then this
     * window has been sitting behind everything for however long the player was in game. Raising it
     * is what makes "log out, pick another character" one motion instead of a hunt through the
     * taskbar.
     */
    private void watchForExit(Process client) {
        runningClient = client;
        Thread watcher =
                new Thread(
                        () -> {
                            try {
                                client.waitFor();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            javax.swing.SwingUtilities.invokeLater(
                                    () -> {
                                        runningClient = null;
                                        setState(NORMAL);
                                        toFront();
                                        requestFocus();
                                        // The one moment an update can be applied without getting
                                        // in anyone's way: they have just stopped playing.
                                        checkForUpdatesQuietly();
                                        // Refresh straight away rather than waiting out the poll:
                                        // somebody just left, and a count that still includes them
                                        // is the one moment it is visibly wrong.
                                        refreshPlayerCount();
                                    });
                        },
                        "rsrealm-client-watch");
        // A daemon, so a client that never exits cannot keep the launcher alive after it is closed.
        watcher.setDaemon(true);
        watcher.start();
    }

    private void signOut() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                backend.signOut();
                return null;
            }

            @Override
            protected void done() {
                session.clear();
                account = null;
                accountChip.setVisible(false);
                // Emptying the list first: setProfiles reveals the row, so hiding before it would
                // be undone.
                setProfiles(List.of());
                characterWrapper.setVisible(false);
                statusLabel.setText(" ");
                setAdminAvailable(null);
                applyState(Phase.SIGNED_OUT);
            }
        }.execute();
    }

    private void showAccount(Backend.Account signedIn) {
        accountName.setText(signedIn.username() == null ? "Player" : signedIn.username());
        Avatar.loadCircular(accountAvatar, signedIn.avatarUrl(), 32, signedIn.username());
        accountChip.setVisible(true);
        accountChip.revalidate();
        // Both a fresh sign-in and a restored session land here, so this is the one place that has
        // to ask.
        refreshAdmin();
    }

    // -------------------------------------------------------------------------------- profiles ----

    private void loadProfiles(boolean restoring) {
        applyState(Phase.WORKING);
        statusLabel.setText("Loading your characters...");
        new SwingWorker<List<Backend.Profile>, Void>() {
            private String failure;
            private Backend.Account fresh;

            @Override
            protected List<Backend.Profile> doInBackground() {
                try {
                    var pair = backend.profilesAndAccount();
                    fresh = pair.getKey();
                    return pair.getValue();
                } catch (Exception e) {
                    failure = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                List<Backend.Profile> profiles = null;
                try {
                    profiles = get();
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                if (profiles == null) {
                    if (restoring && !backend.hasSession()) {
                        // The stored session was rejected — expired or revoked. Start clean rather
                        // than leaving the player looking at a signed-in window that cannot play.
                        session.clear();
                        accountChip.setVisible(false);
                        statusLabel.setText("Please sign in again.");
                    } else {
                        statusLabel.setText(failure == null ? "Couldn't load characters." : failure);
                    }
                    applyState(Phase.SIGNED_OUT);
                    return;
                }
                if (fresh != null) {
                    account = fresh;
                    showAccount(fresh);
                    // Discord names and avatars change; refresh what we cached so the window is not
                    // showing a name the player stopped using months ago.
                    session.save(backend.session(), fresh);
                }
                setProfiles(profiles);
                checkForUpdates();
            }
        }.execute();
    }

    /** The current list, kept so adding a profile does not have to read it back off the dropdown. */
    private List<Backend.Profile> profilesInBox = List.of();

    private void setProfiles(List<Backend.Profile> profiles) {
        profilesInBox = List.copyOf(profiles);
        characterBox.setItems(profiles);
        characterWrapper.setVisible(true);

        String last = session.lastProfile();
        if (last != null) {
            for (Backend.Profile profile : profiles) {
                if (profile.loginUsername().equalsIgnoreCase(last)) {
                    characterBox.setSelected(profile);
                    break;
                }
            }
        }
    }

    private void rememberSelectedProfile() {
        Backend.Profile selected = selectedProfile();
        if (selected != null) {
            session.saveLastProfile(selected.loginUsername());
        }
    }

    private Backend.Profile selectedProfile() {
        return characterBox.getSelected();
    }

    /**
     * Removes the selected character from this Discord account's list.
     *
     * The warning is blunt on purpose, because the consequence is: a launcher-made character has no
     * usable password, so once nothing points at it, nothing reaches it. The character itself is not
     * deleted — its levels and bank stay where they are — but from the player's side that is a
     * distinction without a difference unless staff put the link back.
     */
    private void removeProfile() {
        Backend.Profile profile = selectedProfile();
        if (profile == null) {
            return;
        }
        boolean confirmed =
                Dialogs.confirm(
                        this,
                        "Remove " + profile.displayName() + "?",
                        "This takes the character off your list. It is not deleted — its levels and "
                                + "bank stay — but you will have no way back to it, because a character "
                                + "made in the launcher has no password to log in with.\n\n"
                                + "Only a staff member can put it back.",
                        "Remove",
                        true);
        if (!confirmed) {
            return;
        }
        new SwingWorker<Boolean, Void>() {
            private String failure;

            @Override
            protected Boolean doInBackground() {
                try {
                    backend.unlinkProfile(profile);
                    return true;
                } catch (Exception e) {
                    failure = e.getMessage();
                    return false;
                }
            }

            @Override
            protected void done() {
                boolean removed = false;
                try {
                    removed = Boolean.TRUE.equals(get());
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                if (!removed) {
                    Dialogs.message(
                            LauncherFrame.this,
                            "Couldn't remove it",
                            failure == null ? "The server refused." : failure);
                    return;
                }
                List<Backend.Profile> remaining = new ArrayList<>(profilesInBox);
                remaining.remove(profile);
                setProfiles(remaining);
            }
        }.execute();
    }

    private void createProfile() {
        String name = Dialogs.input(this, "New character", "What should it be called?");
        if (name == null || name.isBlank()) {
            return;
        }
        String trimmed = name.trim();
        new SwingWorker<Backend.Profile, Void>() {
            private String failure;

            @Override
            protected Backend.Profile doInBackground() {
                try {
                    return backend.createProfile(trimmed);
                } catch (Exception e) {
                    failure = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                Backend.Profile created = null;
                try {
                    created = get();
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                if (created == null) {
                    Dialogs.message(
                            LauncherFrame.this,
                            "New character",
                            failure == null ? "Couldn't create that character." : failure);
                    return;
                }
                List<Backend.Profile> all = new ArrayList<>(profilesInBox);
                all.add(created);
                profilesInBox = all;
                setProfiles(all);
                characterBox.setSelected(created);
                rememberSelectedProfile();
            }
        }.execute();
    }

    // ----------------------------------------------------------------------------------- client ----

    /**
     * Load the feed once, then keep it current while the window sits open.
     *
     * The server reads Discord at most once every five minutes and serves everyone the same
     * snapshot, so polling costs a small JSON response and never a Discord request — which is what
     * makes a one-minute interval reasonable rather than rude. It also means the feed cannot be
     * fresher than that cache: a minute here buys reacting quickly once the snapshot turns over,
     * not a minute-old feed.
     */
    private void pollNews() {
        loadNews(true);
        Timer timer = new Timer(NEWS_POLL_MILLIS, e -> loadNews(false));
        timer.setRepeats(true);
        timer.start();
    }

    /**
     * @param firstLoad whether this is the initial fetch. A background refresh must not throw away
     *     what is already on screen: no spinner, and a failed poll leaves the last good feed alone
     *     rather than replacing a readable page with an error over one dropped request.
     */
    private void loadNews(boolean firstLoad) {
        if (firstLoad) {
            newsPanel.setLoading();
        }
        new SwingWorker<List<Backend.News>, Void>() {
            @Override
            protected List<Backend.News> doInBackground() {
                try {
                    return backend.news();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                List<Backend.News> items = null;
                try {
                    items = get();
                } catch (Exception e) {
                    items = null;
                }
                if (items == null) {
                    if (firstLoad) {
                        newsPanel.setError("Couldn't load updates.");
                    }
                    return;
                }
                // Rebuilding the panel resets the scroll position, so an unchanged feed must not
                // touch it — otherwise reading a post would be interrupted every minute.
                if (items.equals(shownNews)) {
                    return;
                }
                shownNews = items;
                newsPanel.setNews(items);
            }
        }.execute();
    }

    // ------------------------------------------------------------------------------------ admin ----

    /**
     * Ask the server whether this account is an admin, and add the tab if it says yes.
     *
     * The question is asked by fetching the data: there is no separate "am I an admin" call to get
     * out of step with the real check. A 403 comes back as null and the tab simply never appears,
     * which is also what happens for a network failure — a launcher that showed an empty Admin tab
     * because the request timed out would be a worse lie than showing nothing.
     *
     * Called after sign-in, so a signed-out launcher never asks.
     */
    private void refreshAdmin() {
        new SwingWorker<Backend.AdminOverview, Void>() {
            @Override
            protected Backend.AdminOverview doInBackground() {
                try {
                    return backend.adminOverview();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                Backend.AdminOverview overview = null;
                try {
                    overview = get();
                } catch (Exception e) {
                    overview = null;
                }
                setAdminAvailable(overview);
            }
        }.execute();
    }

    private void setAdminAvailable(Backend.AdminOverview overview) {
        boolean allowed = overview != null;
        tabs.setVisible(allowed);
        adminTab.setVisible(allowed);
        if (allowed) {
            adminPanel.setOverview(overview);
        } else {
            // Signing out of an admin account must not leave the tab behind for whoever signs in
            // next on the same machine.
            showTab(TAB_NEWS);
        }
        tabs.revalidate();
        tabs.repaint();
    }

    private void showTab(String name) {
        centerCards.show(center, name);
        newsTab.setSelected(TAB_NEWS.equals(name));
        adminTab.setSelected(TAB_ADMIN.equals(name));
        // Reopening Admin should show current numbers, not whatever they were at sign-in.
        if (TAB_ADMIN.equals(name)) {
            refreshAdmin();
        }
    }

    /** A flat text tab. Underlined when selected; there is no box, so it stays out of the way. */
    private static final class TabButton extends JLabel {

        private boolean selected;

        TabButton(String text, Runnable action) {
            super(text);
            setFont(new Font("SansSerif", Font.BOLD, 13));
            setForeground(Theme.SUBTEXT);
            setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mousePressed(MouseEvent e) {
                            action.run();
                        }

                        @Override
                        public void mouseEntered(MouseEvent e) {
                            if (!selected) {
                                setForeground(Theme.TEXT);
                            }
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            if (!selected) {
                                setForeground(Theme.SUBTEXT);
                            }
                        }
                    });
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            setForeground(selected ? Theme.TEXT : Theme.SUBTEXT);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!selected) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(Theme.GOLD);
            g2.fillRect(8, getHeight() - 3, getWidth() - 16, 2);
            g2.dispose();
        }
    }

    /**
     * Notice a new client release while the launcher sits open.
     *
     * The manifest is served by raw.githubusercontent with a five-minute cache header, so asking
     * more often than that returns the same bytes and only burns GitHub's rate limit — the interval
     * matches the cache rather than the minute the news uses.
     *
     * Deliberately quiet: it changes the button and nothing else. No spinner, no status text, and
     * no download starts on its own, because the player is the one who decides when to spend
     * bandwidth. See {@link #canAcceptUpdateNotice()} for when it is allowed to speak at all.
     */
    private void pollClientUpdates() {
        Timer timer = new Timer(UPDATE_POLL_MILLIS, e -> checkForUpdatesQuietly());
        timer.setRepeats(true);
        timer.start();
    }

    private void checkForUpdatesQuietly() {
        if (!canAcceptUpdateNotice()) {
            return;
        }
        new SwingWorker<Manifest, Void>() {
            @Override
            protected Manifest doInBackground() {
                try {
                    return Manifest.fetch(Config.MANIFEST_URL);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                Manifest fetched = null;
                try {
                    fetched = get();
                } catch (Exception e) {
                    fetched = null;
                }
                // A failed poll says nothing. The launcher already has a working client and the
                // next tick will try again; announcing "couldn't reach the update server" over one
                // dropped request would be noise about a problem the player does not have.
                if (fetched == null) {
                    return;
                }
                // Re-checked because the fetch took time: the player may have pressed Play, or a
                // download may have started, while it was in flight.
                if (!canAcceptUpdateNotice()) {
                    return;
                }
                String installed = Installer.installedVersion();
                if (installed == null || installed.equals(fetched.version())) {
                    return;
                }
                remote = fetched;
                statusLabel.setText("Client " + fetched.version() + " is available.");
                applyState(Phase.UPDATE);
            }
        }.execute();
    }

    /**
     * Whether it is safe to turn the Play button into an Update button right now.
     *
     * Only when the launcher is idle with a client already installed. Doing it mid-download would
     * fight the work in progress, and doing it while the game is running would offer to overwrite
     * the jar of a client that is playing on it — the update would be applied under a live process,
     * which on Windows fails outright and elsewhere corrupts a running session.
     */
    private boolean canAcceptUpdateNotice() {
        if (phase != Phase.PLAY && phase != Phase.OFFLINE) {
            return false;
        }
        return runningClient == null || !runningClient.isAlive();
    }

    private void checkForUpdates() {
        applyState(Phase.CHECKING);
        new SwingWorker<Manifest, Void>() {
            @Override
            protected Manifest doInBackground() {
                try {
                    return Manifest.fetch(Config.MANIFEST_URL);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                Manifest fetched = null;
                try {
                    fetched = get();
                } catch (Exception e) {
                    fetched = null;
                }
                if (fetched == null) {
                    if (Installer.installedVersion() != null) {
                        applyState(Phase.OFFLINE);
                    } else {
                        statusLabel.setText("Couldn't reach the update server.");
                        applyState(Phase.ERROR);
                    }
                    return;
                }
                remote = fetched;
                String installed = Installer.installedVersion();
                if (installed == null) {
                    applyState(Phase.DOWNLOAD);
                } else if (!installed.equals(remote.version())) {
                    applyState(Phase.UPDATE);
                } else {
                    applyState(Phase.PLAY);
                }
            }
        }.execute();
    }

    private void onAction() {
        switch (phase) {
            case SIGNED_OUT -> signIn();
            case PLAY, OFFLINE -> play();
            case DOWNLOAD, UPDATE -> downloadClient();
            case ERROR -> checkForUpdates();
            default -> {
                /* CHECKING / WORKING — button is disabled */
            }
        }
    }

    private void downloadClient() {
        applyState(Phase.WORKING);
        progress.setVisible(true);
        progress.setIndeterminate(true);
        new SwingWorker<Void, double[]>() {
            private Exception failure;

            @Override
            protected Void doInBackground() {
                try {
                    Installer.install(
                            remote, (fraction, read, total) -> publish(new double[] {fraction, read, total}));
                } catch (Exception e) {
                    failure = e;
                }
                return null;
            }

            @Override
            protected void process(List<double[]> chunks) {
                double[] last = chunks.get(chunks.size() - 1);
                double fraction = last[0];
                if (fraction < 0) {
                    progress.setIndeterminate(true);
                    statusLabel.setText("Downloading... " + mib(last[1]));
                } else {
                    progress.setIndeterminate(false);
                    progress.setValue(fraction);
                    statusLabel.setText("Downloading... " + mib(last[1]) + " / " + mib(last[2]));
                }
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                progress.setIndeterminate(false);
                if (failure != null) {
                    statusLabel.setText("Download failed: " + failure.getMessage());
                    applyState(Phase.ERROR);
                    return;
                }
                applyState(Phase.PLAY);
            }
        }.execute();
    }

    /**
     * Play: mint a launch token for the chosen character, then start the client with it.
     *
     * The token is fetched at the last possible moment because it is only valid for thirty seconds.
     * Minting it when the window opened, or when the dropdown changed, would hand the client a token
     * that had already expired while the player read the news.
     */
    private void play() {
        Backend.Profile profile = selectedProfile();
        if (profile == null) {
            Dialogs.message(this, "No character", "Create one first with the + button.");
            return;
        }
        applyState(Phase.WORKING);
        statusLabel.setText("Starting...");
        new SwingWorker<String, Void>() {
            private String failure;

            @Override
            protected String doInBackground() {
                try {
                    return backend.launchToken(profile);
                } catch (Exception e) {
                    failure = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                String token = null;
                try {
                    token = get();
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                if (token == null) {
                    statusLabel.setText(failure == null ? "Couldn't start." : failure);
                    applyState(Phase.PLAY);
                    return;
                }
                try {
                    Process client =
                            GameClient.play(token, profile.displayName(), profile.accountId());
                    // The launcher stays open. Closing it would be the last word on a client that
                    // has not finished starting — and it is also where you switch character, so
                    // shutting it down means restarting it to play a second profile.
                    statusLabel.setText("Started " + profile.displayName() + ".");
                    watchForExit(client);
                    Timer ready = new Timer(2500, e -> applyState(Phase.PLAY));
                    ready.setRepeats(false);
                    ready.start();
                } catch (Exception e) {
                    statusLabel.setText("Couldn't launch: " + e.getMessage());
                    applyState(Phase.PLAY);
                }
            }
        }.execute();
    }

    private void applyState(Phase next) {
        this.phase = next;
        versionLabel.setText(remote != null ? "Client " + remote.version() : " ");
        switch (next) {
            case SIGNED_OUT -> {
                actionButton.setText("Sign in with Discord");
                actionButton.setAccent(DISCORD, DISCORD_HOVER);
                actionButton.setEnabled(true);
                characterWrapper.setVisible(false);
            }
            case CHECKING -> {
                statusLabel.setText("Checking for updates...");
                actionButton.setText("Please wait");
                actionButton.setEnabled(false);
            }
            case DOWNLOAD -> {
                statusLabel.setText("Ready to install");
                actionButton.setText("Download");
                actionButton.setAccent(Theme.GOLD, Theme.GOLD_HOVER);
                actionButton.setEnabled(true);
            }
            case UPDATE -> {
                statusLabel.setText("An update is available");
                actionButton.setText("Update");
                actionButton.setAccent(Theme.GOLD, Theme.GOLD_HOVER);
                actionButton.setEnabled(true);
            }
            case PLAY -> {
                statusLabel.setText("You're up to date");
                actionButton.setText("Play");
                actionButton.setAccent(Theme.GREEN, Theme.GREEN_HOVER);
                actionButton.setEnabled(true);
            }
            case OFFLINE -> {
                statusLabel.setText("Offline - playing installed version");
                actionButton.setText("Play");
                actionButton.setAccent(Theme.GREEN, Theme.GREEN_HOVER);
                actionButton.setEnabled(true);
            }
            case WORKING -> {
                actionButton.setText("Working");
                actionButton.setEnabled(false);
            }
            case ERROR -> {
                actionButton.setText("Retry");
                actionButton.setAccent(Theme.RED, Theme.RED.brighter());
                actionButton.setEnabled(true);
            }
        }
    }

    private static String mib(double bytes) {
        if (bytes < 0) {
            return "?";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * The taskbar and window-corner icon.
     *
     * <p>The square crest, not the wordmark. Windows renders this at 16 and 32 pixels, and a
     * 2730x1536 banner squeezed into a square that size is an unreadable dark smudge — which is
     * exactly what it looked like.
     *
     * <p>Several sizes are offered rather than one, so the platform picks the nearest and scales
     * less; downscaling 96 pixels to 16 in one jump loses the shape entirely.
     */
    private void loadWindowIcon() {
        try {
            var url = getClass().getResource("/icon.png");
            if (url == null) {
                return;
            }
            java.awt.image.BufferedImage full = ImageIO.read(url);
            java.util.List<Image> sizes = new java.util.ArrayList<>();
            for (int size : new int[] {16, 24, 32, 48, 64, 96}) {
                java.awt.image.BufferedImage scaled =
                        new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(full, 0, 0, size, size, null);
                g.dispose();
                sizes.add(scaled);
            }
            setIconImages(sizes);
        } catch (Exception ignored) {
            // no icon — fine
        }
    }

    /** Half a minute. Often enough to feel live, rare enough to be nothing on the server. */
    private static final int PLAYER_COUNT_POLL_MILLIS = 30_000;

    /**
     * A minute. The server caches the Discord read for five, so this never reaches Discord — it
     * costs one small JSON response and picks up a new post shortly after that cache turns over.
     */
    private static final int NEWS_POLL_MILLIS = 60_000;

    /**
     * Five minutes, matching the cache header raw.githubusercontent serves the manifest with.
     * Asking more often returns the same bytes from the CDN and buys nothing.
     */
    private static final int UPDATE_POLL_MILLIS = 5 * 60_000;

    /** Card names for the middle of the window. */
    private static final String TAB_NEWS = "news";

    private static final String TAB_ADMIN = "admin";

    /** Discord's brand blurple, so the sign-in button reads as "this opens Discord". */
    private static final Color DISCORD = new Color(0x58, 0x65, 0xF2);

    private static final Color DISCORD_HOVER = new Color(0x6C, 0x78, 0xF5);

    // ------------------------------------------------------------------------------ components ----

    /** The little arrow beside the avatar. Drawn, not a glyph — no font can be missing it. */
    private static final class ChevronDown extends JComponent {
        ChevronDown() {
            setPreferredSize(new Dimension(14, 32));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.SUBTEXT);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g2.drawLine(cx - 4, cy - 2, cx, cy + 2);
            g2.drawLine(cx, cy + 2, cx + 4, cy - 2);
            g2.dispose();
        }
    }

    /** The `+` beside the character dropdown, matching its rounded flat field. */
    private static final class PlusButton extends JComponent {
        private final Runnable action;
        private boolean hovering;

        PlusButton(Runnable action) {
            this.action = action;
            setPreferredSize(new Dimension(40, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mouseEntered(MouseEvent e) {
                            hovering = true;
                            repaint();
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            hovering = false;
                            repaint();
                        }

                        @Override
                        public void mousePressed(MouseEvent e) {
                            action.run();
                        }
                    });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Theme.BACKGROUND);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
            g2.setColor(hovering ? Theme.GOLD : Theme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

            g2.setColor(hovering ? Theme.GOLD : Theme.TEXT);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = w / 2;
            int cy = h / 2;
            g2.drawLine(cx - 6, cy, cx + 6, cy);
            g2.drawLine(cx, cy - 6, cx, cy + 6);
            g2.dispose();
        }
    }

    /** A vector-drawn minimize / close control (no glyph fonts → no encoding surprises). */
    private static final class WindowControl extends JComponent {
        private final boolean close;
        private final Runnable action;
        private boolean hover;

        WindowControl(boolean close, Runnable action) {
            this.close = close;
            this.action = action;
            setPreferredSize(new Dimension(28, 26));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(
                    new MouseAdapter() {
                        @Override
                        public void mouseEntered(MouseEvent e) {
                            hover = true;
                            repaint();
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            hover = false;
                            repaint();
                        }

                        @Override
                        public void mouseClicked(MouseEvent e) {
                            action.run();
                        }
                    });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (hover) {
                g2.setColor(close ? Theme.RED : Theme.BORDER);
                g2.fillRoundRect(0, 0, w, h, 6, 6);
            }
            g2.setColor(hover ? Color.WHITE : Theme.SUBTEXT);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = w / 2;
            int cy = h / 2;
            int r = 5;
            if (close) {
                g2.drawLine(cx - r, cy - r, cx + r, cy + r);
                g2.drawLine(cx - r, cy + r, cx + r, cy - r);
            } else {
                g2.drawLine(cx - r, cy + r - 1, cx + r, cy + r - 1);
            }
            g2.dispose();
        }
    }

    /** A flat, rounded progress bar with determinate + animated indeterminate modes. */
    private static final class RoundBar extends JComponent {
        private double value;
        private boolean indeterminate;
        private int anim;
        private final Timer timer;

        RoundBar() {
            setPreferredSize(new Dimension(0, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
            timer =
                    new Timer(
                            16,
                            e -> {
                                anim = (anim + 6) % 1000;
                                repaint();
                            });
        }

        void setIndeterminate(boolean b) {
            indeterminate = b;
            if (b && isVisible()) {
                timer.start();
            } else {
                timer.stop();
            }
            repaint();
        }

        void setValue(double v) {
            value = Math.max(0, Math.min(1, v));
            repaint();
        }

        @Override
        public void setVisible(boolean visible) {
            super.setVisible(visible);
            if (!visible) {
                timer.stop();
            } else if (indeterminate) {
                timer.start();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Theme.TRACK);
            g2.fillRoundRect(0, 0, w, h, h, h);
            g2.setColor(Theme.GOLD);
            if (indeterminate) {
                int seg = Math.max(h, w / 3);
                int x = (int) ((anim / 1000.0) * (w + seg)) - seg;
                int drawX = Math.max(0, x);
                int drawW = Math.min(seg, w - drawX);
                if (drawW > 0) {
                    g2.fillRoundRect(drawX, 0, drawW, h, h, h);
                }
            } else {
                int fw = (int) (value * w);
                if (fw > 0) {
                    g2.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
                }
            }
            g2.dispose();
        }
    }
}
