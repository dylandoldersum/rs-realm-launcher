package rs.realm.launcher;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

    private final NewsPanel newsPanel = new NewsPanel();
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel versionLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel accountName = new JLabel();
    private final JLabel accountAvatar = new JLabel();
    private final JPanel accountChip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final RoundBar progress = new RoundBar();
    private final Theme.AccentButton actionButton = new Theme.AccentButton("Sign in with Discord");
    private final JComboBox<Backend.Profile> characterBox = new JComboBox<>();
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
        root.add(newsPanel, BorderLayout.CENTER);
        root.add(buildSidePanel(), BorderLayout.EAST);
        setContentPane(root);

        actionButton.addActionListener(e -> onAction());

        loadNews();
        restoreSession();
    }

    // ------------------------------------------------------------------------------ title bar ----

    private JComponent buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.TITLE_BAR);
        bar.setPreferredSize(new Dimension(0, 52));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 8));

        JLabel title = new JLabel(Config.BRAND);
        title.setForeground(Theme.TEXT);
        title.setFont(new Font("SansSerif", Font.BOLD, 15));
        bar.add(title, BorderLayout.WEST);

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

        JPopupMenu menu = new JPopupMenu();
        JMenuItem signOut = new JMenuItem("Sign out");
        signOut.addActionListener(e -> signOut());
        menu.add(signOut);

        MouseAdapter open =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        menu.show(accountChip, 0, accountChip.getHeight());
                    }
                };
        accountChip.addMouseListener(open);
        accountName.addMouseListener(open);
        accountAvatar.addMouseListener(open);
        return accountChip;
    }

    // ----------------------------------------------------------------------------- side panel ----

    private JComponent buildSidePanel() {
        JPanel side = new JPanel();
        side.setBackground(Theme.PANEL);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(340, 0));
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(24, 26, 24, 26)));

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

        statusLabel.setForeground(Theme.SUBTEXT);
        statusLabel.setFont(Theme.BODY);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        side.add(statusLabel);

        side.add(Box.createVerticalGlue());

        versionLabel.setForeground(Theme.SUBTEXT);
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

        JLabel label = new JLabel("Character");
        label.setForeground(Theme.SUBTEXT);
        label.setFont(Theme.SMALL);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(label);
        wrapper.add(Box.createVerticalStrut(6));

        characterBox.setBackground(Theme.BACKGROUND);
        characterBox.setForeground(Theme.TEXT);
        characterBox.setFont(new Font("SansSerif", Font.PLAIN, 14));
        characterBox.setPreferredSize(new Dimension(0, 38));
        characterBox.addActionListener(e -> rememberSelectedProfile());

        JButton add = new JButton("+");
        add.setToolTipText("Create another character");
        add.setFocusPainted(false);
        add.setFont(new Font("SansSerif", Font.BOLD, 18));
        add.setForeground(Theme.TEXT);
        add.setBackground(Theme.BACKGROUND);
        add.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        add.setPreferredSize(new Dimension(38, 38));
        add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add.addActionListener(e -> createProfile());

        characterRow.setOpaque(false);
        characterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        characterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        characterRow.add(characterBox, BorderLayout.CENTER);
        characterRow.add(add, BorderLayout.EAST);
        wrapper.add(characterRow);

        // Nothing to choose between until we know who you are.
        wrapper.setVisible(false);
        characterWrapper = wrapper;
        return wrapper;
    }

    private JComponent characterWrapper;

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
                characterWrapper.setVisible(false);
                characterBox.setModel(new DefaultComboBoxModel<>());
                statusLabel.setText(" ");
                applyState(Phase.SIGNED_OUT);
            }
        }.execute();
    }

    private void showAccount(Backend.Account signedIn) {
        accountName.setText(signedIn.username() == null ? "Player" : signedIn.username());
        Avatar.loadCircular(accountAvatar, signedIn.avatarUrl(), 32, signedIn.username());
        accountChip.setVisible(true);
        accountChip.revalidate();
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

    private void setProfiles(List<Backend.Profile> profiles) {
        DefaultComboBoxModel<Backend.Profile> model = new DefaultComboBoxModel<>();
        for (Backend.Profile profile : profiles) {
            model.addElement(profile);
        }
        characterBox.setModel(model);
        characterWrapper.setVisible(true);

        String last = session.lastProfile();
        if (last != null) {
            for (int i = 0; i < model.getSize(); i++) {
                if (model.getElementAt(i).loginUsername().equalsIgnoreCase(last)) {
                    characterBox.setSelectedIndex(i);
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
        Object selected = characterBox.getSelectedItem();
        return selected instanceof Backend.Profile profile ? profile : null;
    }

    private void createProfile() {
        String name =
                JOptionPane.showInputDialog(
                        this, "Character name", "New character", JOptionPane.PLAIN_MESSAGE);
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
                    JOptionPane.showMessageDialog(
                            LauncherFrame.this,
                            failure == null ? "Couldn't create that character." : failure,
                            "New character",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                List<Backend.Profile> all = new ArrayList<>();
                DefaultComboBoxModel<Backend.Profile> model =
                        (DefaultComboBoxModel<Backend.Profile>) characterBox.getModel();
                for (int i = 0; i < model.getSize(); i++) {
                    all.add(model.getElementAt(i));
                }
                all.add(created);
                setProfiles(all);
                characterBox.setSelectedItem(created);
            }
        }.execute();
    }

    // ----------------------------------------------------------------------------------- client ----

    private void loadNews() {
        newsPanel.setLoading();
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
                    newsPanel.setError("Couldn't load updates.");
                } else {
                    newsPanel.setNews(items);
                }
            }
        }.execute();
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
            JOptionPane.showMessageDialog(
                    this,
                    "Create a character first with the + button.",
                    "No character",
                    JOptionPane.INFORMATION_MESSAGE);
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
                    GameClient.play(token, profile.displayName());
                    statusLabel.setText("Launching...");
                    Timer close = new Timer(1200, e -> System.exit(0));
                    close.setRepeats(false);
                    close.start();
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

    private void loadWindowIcon() {
        try {
            var url = getClass().getResource("/logo.png");
            if (url != null) {
                setIconImage(ImageIO.read(url));
            }
        } catch (Exception ignored) {
            // no icon — fine
        }
    }

    /** Discord's brand blurple, so the sign-in button reads as "this opens Discord". */
    private static final Color DISCORD = new Color(0x58, 0x65, 0xF2);

    private static final Color DISCORD_HOVER = new Color(0x6C, 0x78, 0xF5);

    // ------------------------------------------------------------------------------ components ----

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
