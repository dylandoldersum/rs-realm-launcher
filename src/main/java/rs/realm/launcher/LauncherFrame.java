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
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** The launcher window: checks the manifest, then offers Download / Update / Play. */
public final class LauncherFrame extends JFrame {

    private enum Phase {
        CHECKING,
        DOWNLOAD,
        UPDATE,
        PLAY,
        OFFLINE,
        WORKING,
        ERROR
    }

    private Manifest remote;
    private Phase phase = Phase.CHECKING;

    private final JLabel statusLabel = new JLabel("Checking for updates...", SwingConstants.CENTER);
    private final JLabel versionLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JTextArea changelog = new JTextArea();
    private final RoundBar progress = new RoundBar();
    private final Theme.AccentButton actionButton = new Theme.AccentButton("Please wait");

    public LauncherFrame() {
        setTitle(Config.BRAND + " Launcher");
        setUndecorated(true);
        setSize(400, 520);
        setMinimumSize(new Dimension(400, 520));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        loadWindowIcon();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        root.add(buildTitleBar(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);

        actionButton.addActionListener(e -> onAction());
        checkForUpdates();
    }

    // ----------------------------------------------------------------------------------------- UI

    private JComponent buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.TITLE_BAR);
        bar.setPreferredSize(new Dimension(0, 36));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 8));

        JLabel title = new JLabel(Config.BRAND);
        title.setForeground(Theme.TEXT);
        title.setFont(new Font("SansSerif", Font.BOLD, 13));
        bar.add(title, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 5));
        buttons.setOpaque(false);
        buttons.add(new WindowControl(false, () -> setState(ICONIFIED)));
        buttons.add(new WindowControl(true, () -> System.exit(0)));
        bar.add(buttons, BorderLayout.EAST);

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
                        setLocation(
                            getX() + e.getX() - anchor[0].x, getY() + e.getY() - anchor[0].y);
                    }
                }
            };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        title.addMouseListener(drag);
        title.addMouseMotionListener(drag);
        return bar;
    }

    private JComponent buildBody() {
        JPanel body = new JPanel();
        body.setBackground(Theme.BACKGROUND);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(26, 28, 10, 28));

        JComponent logo = buildLogo();
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(logo);
        body.add(Box.createVerticalStrut(10));

        JLabel brand = new JLabel(Config.BRAND);
        brand.setForeground(Theme.TEXT);
        brand.setFont(Theme.H1);
        brand.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(brand);
        body.add(Box.createVerticalStrut(3));

        statusLabel.setForeground(Theme.SUBTEXT);
        statusLabel.setFont(Theme.BODY);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        body.add(statusLabel);
        body.add(Box.createVerticalStrut(16));

        // Thin gold accent divider.
        JComponent divider = new AccentDivider();
        divider.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(divider);
        body.add(Box.createVerticalStrut(14));

        JLabel notesTitle = new JLabel("LATEST CHANGES");
        notesTitle.setForeground(Theme.GOLD);
        notesTitle.setFont(new Font("SansSerif", Font.BOLD, 10));
        JPanel notesHeader = new JPanel(new BorderLayout());
        notesHeader.setOpaque(false);
        notesHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        notesHeader.add(notesTitle, BorderLayout.WEST);
        body.add(notesHeader);
        body.add(Box.createVerticalStrut(5));

        changelog.setEditable(false);
        changelog.setLineWrap(true);
        changelog.setWrapStyleWord(true);
        changelog.setBackground(Theme.PANEL);
        changelog.setForeground(Theme.TEXT);
        changelog.setFont(Theme.BODY);
        changelog.setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 11));
        JScrollPane scroll =
            new JScrollPane(
                changelog,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(scroll);
        return body;
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
            // Fit within a box while preserving the original aspect ratio (no squishing).
            int maxW = 150;
            int maxH = 96;
            double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
            int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            JLabel label = new JLabel(new ImageIcon(scaled));
            label.setPreferredSize(new Dimension(w, h));
            label.setMaximumSize(new Dimension(w, h));
            return label;
        }
        JPanel badge =
            new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Theme.GOLD);
                    g2.fillOval(0, 0, 88, 88);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("SansSerif", Font.BOLD, 40));
                    var fm = g2.getFontMetrics();
                    String s = Config.BRAND.substring(0, 1);
                    g2.drawString(
                        s, (88 - fm.stringWidth(s)) / 2, (88 - fm.getHeight()) / 2 + fm.getAscent());
                    g2.dispose();
                }
            };
        badge.setOpaque(false);
        badge.setPreferredSize(new Dimension(88, 88));
        badge.setMaximumSize(new Dimension(88, 88));
        return badge;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel();
        footer.setBackground(Theme.BACKGROUND);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 28, 20, 28));

        progress.setVisible(false);
        progress.setAlignmentX(Component.CENTER_ALIGNMENT);
        footer.add(progress);
        footer.add(Box.createVerticalStrut(10));

        actionButton.setEnabled(false);
        actionButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        actionButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        footer.add(actionButton);
        footer.add(Box.createVerticalStrut(6));

        versionLabel.setForeground(Theme.SUBTEXT);
        versionLabel.setFont(Theme.SMALL);
        versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        versionLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        footer.add(versionLabel);
        return footer;
    }

    // -------------------------------------------------------------------------------------- Logic

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
                changelog.setText(
                    remote.changelog().isEmpty()
                        ? "Version " + remote.version()
                        : remote.changelog());
                changelog.setCaretPosition(0);

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
            case PLAY, OFFLINE -> launchClient();
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
                        remote,
                        (fraction, read, total) -> publish(new double[] {fraction, read, total}));
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

    private void launchClient() {
        try {
            GameClient.play();
            statusLabel.setText("Launching...");
            actionButton.setEnabled(false);
            Timer t = new Timer(1200, e -> System.exit(0));
            t.setRepeats(false);
            t.start();
        } catch (Exception e) {
            statusLabel.setText("Couldn't launch: " + e.getMessage());
        }
    }

    private void applyState(Phase next) {
        this.phase = next;
        versionLabel.setText(remote != null ? "Client " + remote.version() : " ");
        switch (next) {
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
                actionButton.setText("Play now");
                actionButton.setAccent(Theme.GREEN, Theme.GREEN_HOVER);
                actionButton.setEnabled(true);
            }
            case OFFLINE -> {
                statusLabel.setText("Offline - playing installed version");
                actionButton.setText("Play now");
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

    // ------------------------------------------------------------------------------- Components

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

    /** A thin, horizontally-centered gold accent line. */
    private static final class AccentDivider extends JComponent {
        AccentDivider() {
            setPreferredSize(new Dimension(0, 2));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int lineW = Math.min(60, w);
            g2.setColor(Theme.GOLD);
            g2.fillRoundRect((w - lineW) / 2, 0, lineW, 2, 2, 2);
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
