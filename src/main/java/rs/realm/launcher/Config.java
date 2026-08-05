package rs.realm.launcher;

import java.nio.file.Path;

/**
 * All the knobs you actually edit. The launcher checks {@link #MANIFEST_URL} for the latest client
 * version and downloads the client jar it points at into {@link #INSTALL_DIR}.
 */
public final class Config {
    private Config() {}

    /** Display name used in the window title / header. */
    public static final String BRAND = "RS-Realm";

    /**
     * Raw URL of the version manifest (a small key=value file you bump on GitHub). Point this at a raw
     * GitHub URL, e.g.:
     *   https://raw.githubusercontent.com/&lt;user&gt;/&lt;repo&gt;/main/version.properties
     *
     * The manifest contains:
     *   version   = 0.1.0
     *   url       = https://github.com/&lt;user&gt;/&lt;repo&gt;/releases/download/v0.1.0/fiddled-client.jar
     *   sha256    = &lt;hex&gt;            (optional — leave blank to skip integrity check)
     *   changelog = What's new...      (optional, shown in the launcher)
     */
    public static final String MANIFEST_URL =
        System.getProperty(
            "rsrealm.manifest",
            "https://raw.githubusercontent.com/dylandoldersum/rs-realm-launcher/main/version.properties");

    /** Where the client jar + install metadata live (per-user). */
    public static final Path INSTALL_DIR = Path.of(System.getProperty("user.home"), ".rs-realm");

    /** The downloaded, runnable client (the Fiddled fat jar). */
    public static final Path CLIENT_JAR = INSTALL_DIR.resolve("fiddled-client.jar");

    /** Tracks which version is currently installed. */
    public static final Path INSTALLED_FILE = INSTALL_DIR.resolve("installed.properties");

    /** Client stdout/stderr is redirected here (handy for beta bug reports). */
    public static final Path CLIENT_LOG = INSTALL_DIR.resolve("client.log");

    /**
     * The RS-Realm backend: Discord login, profiles, launch tokens and the update feed.
     *
     * A domain rather than an IP on purpose — the server has moved hosts once already, and an IP in
     * a shipped launcher means every player needs a new launcher when it moves again.
     */
    public static final String API_BASE_URL =
        System.getProperty("rsrealm.api", "http://play.rs-realm.com:43595");

    /** Where the signed-in session is remembered between launches. */
    public static final Path SESSION_FILE = INSTALL_DIR.resolve("session.properties");

    /**
     * Hands the token to the gamepack's own account mode instead of the login form. TESTED, AND IT
     * DOES NOT WORK. Kept only so nobody spends another evening rediscovering that.
     *
     * <p>Account mode is the route that produces a real "Play Now" screen with the character's name
     * on it. It draws that screen perfectly and then, on the click, never opens a game connection at
     * all — it goes looking for Jagex's own authentication servers.
     *
     * <p>Tried twice, with opposite arrangements. First with the token in {@code JX_ACCESS_TOKEN};
     * then in rsprox's exact shape, token in {@code JX_SESSION_ID} with access and refresh left
     * empty, which is the arrangement that demonstrably gets rsprox's client into account mode
     * against a local server. Neither produced a single token login on the server, and the server
     * logs BOTH acceptance and rejection — so the silence is evidence rather than an absence of it.
     *
     * <p>The working route is the ordinary login form, filled in by the client's own plugin. The
     * only visible difference is one frame of login screen.
     *
     * <p>Still reachable with {@code -Drsrealm.jagexmode=true} for anyone who wants to try again
     * against a future client revision.
     */
    public static final boolean JAGEX_MODE =
        Boolean.parseBoolean(System.getProperty("rsrealm.jagexmode", "false"));

    /** Extra JVM args passed to the client process (heap, etc.). */
    public static final String[] CLIENT_JVM_ARGS = {"-Xmx768m", "-Xss2m"};
}
