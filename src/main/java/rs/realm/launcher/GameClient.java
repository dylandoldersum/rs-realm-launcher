package rs.realm.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Launches the downloaded client jar with the same Java runtime that runs the launcher. */
public final class GameClient {
    private GameClient() {}

    /**
     * Starts the client and hands it a launch token, so the player never sees a password box.
     *
     * <p>Two routes, and the token is the same string either way. By default it travels as a system
     * property, which the client's own plugin reads and types into the ordinary login form on the
     * player's behalf. Under {@link Config#JAGEX_MODE} it ALSO goes into the {@code JX_*} variables,
     * which puts the gamepack into Jagex-account mode: a real "Play Now" screen with the character's
     * name on it and no login form at all. That route works, and needs a client whose jav_config
     * points at our auth host — see {@link Config#JAGEX_MODE}.
     *
     * @param launchToken the one-shot token from the backend, or null to start the client with the
     *     ordinary login screen.
     * @param displayName the profile being played. Only fills the username field — the server takes
     *     the profile from the token, so this cannot log anyone into the wrong account.
     */
    public static Process play(String launchToken, String displayName, int accountId)
            throws IOException {
        return start(launchToken, displayName, accountId);
    }

    /** Starts the client with no token — the plain login screen. */
    public static Process play() throws IOException {
        return start(null, null, 0);
    }

    private static Process start(String launchToken, String displayName, int accountId)
            throws IOException {
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
        boolean hasToken = launchToken != null && !launchToken.isBlank();
        if (hasToken) {
            command.add("-Drsrealm.launchtoken=" + launchToken);
            if (displayName != null && !displayName.isBlank()) {
                command.add("-Drsrealm.displayname=" + displayName);
            }
            if (Config.JAGEX_MODE) {
                // Tells the agent to leave the JX_* variables alone. Without it they are stripped,
                // which is the right default for everyone who is not running this experiment.
                command.add("-Drsrealm.jagexmode=true");
            }
        }
        command.add("-jar");
        command.add(Config.CLIENT_JAR.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        if (hasToken && Config.JAGEX_MODE) {
            // Exactly rsprox's shape, and the arrangement that works. ACCESS and REFRESH stay
            // EMPTY — a filled access token is something the gamepack goes off to validate
            // upstream. The session id is the launch token, so it comes back to us as the bearer
            // on the gamepack's own token call.
            pb.environment().put("JX_SESSION_ID", launchToken);
            pb.environment().put("JX_CHARACTER_ID", Integer.toString(accountId));
            pb.environment().put("JX_DISPLAY_NAME", displayName == null ? "" : displayName);
            pb.environment().put("JX_ACCESS_TOKEN", "");
            pb.environment().put("JX_REFRESH_TOKEN", "");
        }
        pb.directory(Config.INSTALL_DIR.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(Config.CLIENT_LOG.toFile());
        return pb.start();
    }
}
