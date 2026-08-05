package rs.realm.launcher;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point — shows the launcher window on the Swing thread. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to the default L&F; the launcher paints its own dark theme anyway.
        }
        SwingUtilities.invokeLater(() -> new LauncherFrame().setVisible(true));
    }
}
