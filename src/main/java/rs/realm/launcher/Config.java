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
     *
     * <p>HTTPS, through the reverse proxy in front of the game server's plain HTTP port. Everything
     * this carries is a credential: the Discord exchange, the session token attached to every call,
     * and the launch token that logs a player in. Over plain HTTP all three travel in the clear on
     * whatever network the player happens to be on.
     */
    public static final String API_BASE_URL =
        System.getProperty("rsrealm.api", "https://auth.rs-realm.com");

    /** Where the signed-in session is remembered between launches. */
    public static final Path SESSION_FILE = INSTALL_DIR.resolve("session.properties");

    /**
     * Hands the token to the gamepack's own account mode instead of the login form. WORKS — it
     * produces the real "Play Now" screen with the character's name on it and no password box at
     * all.
     *
     * <p>The token goes in {@code JX_SESSION_ID}, with access and refresh left EMPTY (rsprox's
     * shape). The gamepack then fetches a login token over HTTPS and sends it through the login
     * protocol, where the server redeems it exactly as it does from the password field.
     *
     * <p>Four separate things had to be right, and each failed in a way that looked like the
     * others. The auth HOST comes from the hosted jav_config's applet params, not from the client
     * jar. That host must keep the {@code /jagex/} path prefix, must be HTTPS (the gamepack casts
     * the connection to {@code HttpsURLConnection}), and must answer the token call with the bare
     * token as plain text — the gamepack does not parse that response, it swallows the whole body.
     *
     * <p>Requires a client whose jav_config points at our auth host. A client built against the
     * stock config still reaches Jagex and cannot log in, which is why this is not on by default.
     *
     * <p>The fallback stays: without this, the client's own plugin types the launch token into the
     * ordinary login form. The only visible difference is one frame of login screen.
     */
    public static final boolean JAGEX_MODE =
        Boolean.parseBoolean(System.getProperty("rsrealm.jagexmode", "false"));

    /** Extra JVM args passed to the client process (heap, etc.). */
    public static final String[] CLIENT_JVM_ARGS = {"-Xmx768m", "-Xss2m"};
}
