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
     * Experimental: hand the token to the gamepack's own account mode instead of the login form.
     *
     * Account mode is the route that produces a real "Play Now" screen with the character's name on
     * it, rather than a login form filled in for you. An earlier attempt at it failed — the client
     * never opened a game connection — but that attempt put the token in {@code JX_ACCESS_TOKEN},
     * and rsprox, which demonstrably gets a client into account mode against a local server, leaves
     * that variable EMPTY and carries its value in {@code JX_SESSION_ID}.
     *
     * <p>Off by default and deliberately a flag, because this is an either/or: in account mode there
     * is no password field, so the working form-fill cannot stand behind it as a fallback. Turning it
     * on is a choice to test, not a default anybody inherits.
     *
     * <p>Enable with {@code -Drsrealm.jagexmode=true}.
     */
    public static final boolean JAGEX_MODE =
        Boolean.parseBoolean(System.getProperty("rsrealm.jagexmode", "false"));

    /** Extra JVM args passed to the client process (heap, etc.). */
    public static final String[] CLIENT_JVM_ARGS = {"-Xmx768m", "-Xss2m"};
}
