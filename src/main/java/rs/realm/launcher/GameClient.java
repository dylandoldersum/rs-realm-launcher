package rs.realm.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Launches the downloaded client jar with the same Java runtime that runs the launcher. */
public final class GameClient {
    private GameClient() {}

    /**
     * Starts the client and hands it a launch token, so it can log in without a password screen.
     *
     * The token is passed BOTH ways on purpose:
     *
     * <ul>
     *   <li>{@code -Drsrealm.launchtoken} — the agent can always read a system property, whatever
     *       the client ends up doing with it.
     *   <li>{@code JX_ACCESS_TOKEN} / {@code JX_DISPLAY_NAME} — the shape the OSRS gamepack itself
     *       reads when it runs in Jagex-account mode, which is the route that produces a real "Play
     *       Now" screen instead of a login form.
     * </ul>
     *
     * The second one does nothing yet: the client's agent currently STRIPS every {@code JX_*}
     * variable, deliberately, because players who also ran the real Jagex launcher were landing on
     * an account screen that authenticates against Jagex and cannot work on a private server.
     * Setting them here costs nothing and means the launcher side is already done whichever way that
     * decision lands.
     *
     * @param launchToken the one-shot token from the backend, or null to start the client with the
     *     ordinary login screen.
     * @param displayName the profile being played, shown by the gamepack in Jagex-account mode.
     */
    public static Process play(String launchToken, String displayName) throws IOException {
        return start(launchToken, displayName);
    }

    /** Starts the client with no token — the plain login screen. */
    public static Process play() throws IOException {
        return start(null, null);
    }

    private static Process start(String launchToken, String displayName) throws IOException {
        if (!Files.exists(Config.CLIENT_JAR)) {
            throw new IOException("Client is not installed yet.");
        }

        // Use the SAME JVM that runs the launcher — when packaged via jpackage this points at the
        // bundled runtime inside the installed app, so the player doesn't need any system Java.
        // On Windows prefer javaw.exe over java.exe: java.exe is a console binary and would flash
        // a DOS window between the launcher hitting Play and the client's Swing UI appearing.
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String binDir = javaHome + java.io.File.separator + "bin" + java.io.File.separator;
        String javaBin;
        if (windows) {
            String jw = binDir + "javaw.exe";
            javaBin = Files.exists(java.nio.file.Paths.get(jw)) ? jw : binDir + "java.exe";
        } else {
            javaBin = binDir + "java";
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        for (String arg : Config.CLIENT_JVM_ARGS) {
            command.add(arg);
        }
        if (launchToken != null && !launchToken.isBlank()) {
            command.add("-Drsrealm.launchtoken=" + launchToken);
            if (displayName != null && !displayName.isBlank()) {
                command.add("-Drsrealm.displayname=" + displayName);
            }
        }
        command.add("-jar");
        command.add(Config.CLIENT_JAR.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        if (launchToken != null && !launchToken.isBlank()) {
            pb.environment().put("JX_ACCESS_TOKEN", launchToken);
            pb.environment().put("JX_SESSION_ID", launchToken);
            if (displayName != null && !displayName.isBlank()) {
                pb.environment().put("JX_DISPLAY_NAME", displayName);
                pb.environment().put("JX_CHARACTER_ID", displayName);
            }
        }
        pb.directory(Config.INSTALL_DIR.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(Config.CLIENT_LOG.toFile());
        return pb.start();
    }
}
