package rs.realm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * The entry point of the INSTALLED app, and the reason a UI change does not need a reinstall.
 *
 * <p>The native bundle is three things: the launcher executable, a bundled Java runtime, and one jar
 * holding all of the launcher's code. Only that last part changes with any regularity, so this class
 * keeps it up to date on its own: check a manifest, download the new jar, verify it, and hand over.
 *
 * <p>Replacing the bundled jar in place is not an option even though the install is per-user and
 * writable. The JVM holds it open for as long as the launcher runs, Windows will not let an open
 * file be replaced, and it is already open before any of our code runs. So the jar that actually
 * runs the UI lives in {@code ~/.rs-realm/launcher/} instead, where nothing has it open at the
 * moment we want to write it.
 *
 * <h2>Deliberately self-contained</h2>
 *
 * This class shares no code with the rest of the launcher, not even {@link Config}. It is the one
 * piece that CANNOT update itself — changing it means shipping a new installer — so it must not
 * depend on anything it is about to replace, and it should have as few reasons to change as we can
 * manage. That is worth a little duplication.
 *
 * <h2>Never leave the player with nothing</h2>
 *
 * Every failure path falls back to the newest jar already on disk, and the installer ships one as a
 * seed so that even a first run with no network gets a working launcher. A bad upload or an outage
 * at GitHub should cost players an update, not their launcher — and a launcher that will not start
 * cannot be repaired by uploading a better one.
 */
public final class Bootstrap {
    /**
     * Where the manifest lives.
     *
     * Served by raw.githubusercontent with a five minute cache, so an update reaches players a few
     * minutes after it is published rather than instantly. That is fine for this; it is only worth
     * knowing when a change appears not to have taken effect.
     */
    private static final String MANIFEST_URL =
        System.getProperty(
            "rsrealm.launchermanifest",
            "https://raw.githubusercontent.com/dylandoldersum/rs-realm-launcher/main/launcher.properties");

    private static final Path HOME = Path.of(System.getProperty("user.home"), ".rs-realm", "launcher");
    private static final Path DOWNLOADED_JAR = HOME.resolve("rs-realm-launcher.jar");
    private static final Path DOWNLOADED_META = HOME.resolve("installed.properties");

    /** The jar that ships inside the bundle, used when nothing better has been downloaded yet. */
    private static final String SEED_JAR = "rs-realm-launcher.jar";

    private static final String MAIN_CLASS = "rs.realm.launcher.Launcher";

    /** Short on purpose: a slow or unreachable manifest must delay startup, not prevent it. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private Bootstrap() {}

    public static void main(String[] args) throws Exception {
        Path seed = seedJar();
        String seedVersion = versionOf(seed);
        String localVersion = downloadedVersion();

        // Whatever is newest right now, before any network call. If the update fails, this is what
        // runs, and it is also what the update has to beat to be worth installing.
        Path best = DOWNLOADED_JAR;
        String bestVersion = localVersion;
        if (!Files.isRegularFile(DOWNLOADED_JAR) || isNewer(seedVersion, localVersion)) {
            best = seed;
            bestVersion = seedVersion;
        }

        try {
            Path updated = update(bestVersion);
            if (updated != null) {
                best = updated;
            }
        } catch (Exception e) {
            // Deliberately swallowed. Being unable to check for updates is not a reason to refuse
            // to start; it is a reason to start what we already have.
            log("update check failed: " + e);
        }

        if (best == null || !Files.isRegularFile(best)) {
            fail("The launcher could not start because no launcher files were found.\n"
                + "Reinstalling RS-Realm will fix this.");
            return;
        }

        log("starting " + best);
        launch(best, args);
    }

    /**
     * Downloads a newer launcher if the manifest offers one.
     *
     * @return the new jar, or null if there was nothing newer or the update did not complete.
     */
    private static Path update(String currentVersion) throws IOException {
        Properties manifest = fetchManifest();
        String version = manifest.getProperty("version", "").trim();
        String url = manifest.getProperty("url", "").trim();
        String sha256 = manifest.getProperty("sha256", "").trim();

        if (version.isEmpty() || url.isEmpty()) {
            log("manifest has no version or url; skipping");
            return null;
        }
        if (!isNewer(version, currentVersion)) {
            log("up to date at " + currentVersion);
            return null;
        }

        log("updating " + currentVersion + " -> " + version);
        Files.createDirectories(HOME);
        Path temp = Files.createTempFile(HOME, "download", ".jar");
        try {
            download(url, temp);

            // Verified BEFORE it replaces anything. An unverified jar is code we are about to run
            // with the player's account token in its hands.
            if (!sha256.isEmpty()) {
                String actual = sha256(temp);
                if (!actual.equalsIgnoreCase(sha256)) {
                    log("checksum mismatch: expected " + sha256 + " got " + actual);
                    return null;
                }
            }
            if (!isRunnableJar(temp)) {
                log("downloaded file is not a runnable launcher jar");
                return null;
            }

            Files.move(temp, DOWNLOADED_JAR, StandardCopyOption.REPLACE_EXISTING);
            Properties meta = new Properties();
            meta.setProperty("version", version);
            try (OutputStream out = Files.newOutputStream(DOWNLOADED_META)) {
                meta.store(out, "RS-Realm launcher - installed UI version");
            }
            log("updated to " + version);
            return DOWNLOADED_JAR;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Runs the launcher out of [jar].
     *
     * <p>The parent is the PLATFORM loader, not the application loader. The seed jar sits on the
     * application classpath, so delegating to it would find the bundled copy of every class first
     * and quietly run the old launcher no matter what was downloaded.
     */
    private static void launch(Path jar, String[] args) throws Exception {
        URL url = jar.toUri().toURL();
        URLClassLoader loader =
            new URLClassLoader("launcher", new URL[] {url}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> main = Class.forName(MAIN_CLASS, true, loader);
        main.getMethod("main", String[].class).invoke(null, (Object) args);
    }

    private static Properties fetchManifest() throws IOException {
        HttpURLConnection conn = open(MANIFEST_URL);
        try (InputStream in = conn.getInputStream()) {
            Properties props = new Properties();
            props.load(in);
            return props;
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, Path target) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("bad url: " + url, e);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "RS-Realm-Bootstrap");
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IOException("HTTP " + code + " for " + url);
        }
        return conn;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /** Guards against a truncated download or an HTML error page that happened to arrive with a 200. */
    private static boolean isRunnableJar(Path file) {
        try (JarFile jar = new JarFile(file.toFile())) {
            return jar.getEntry(MAIN_CLASS.replace('.', '/') + ".class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The jar shipped in the bundle, which sits next to this one inside the app directory. */
    private static Path seedJar() {
        try {
            Path self =
                Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = self.getParent();
            return dir == null ? null : dir.resolve(SEED_JAR);
        } catch (Exception e) {
            log("could not locate the bundled jar: " + e);
            return null;
        }
    }

    /** Reads `Implementation-Version` out of a jar manifest. */
    private static String versionOf(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            java.util.jar.Manifest manifest = file.getManifest();
            if (manifest == null) {
                return null;
            }
            return manifest.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }

    private static String downloadedVersion() {
        if (!Files.isRegularFile(DOWNLOADED_META)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(DOWNLOADED_META)) {
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            return v == null || v.isBlank() ? null : v.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Numeric dotted comparison, so 1.10.0 beats 1.9.0 rather than losing to it alphabetically.
     *
     * <p>An unknown current version counts as older, which is what makes a fresh install download
     * the current release instead of sitting on the seed forever.
     */
    private static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = part(a, i);
            int y = part(b, i);
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Goes to the same place the client's output does, so one file explains a failed start. */
    private static void log(String message) {
        System.out.println("[bootstrap] " + message);
    }

    private static void fail(String message) {
        log(message);
        try {
            javax.swing.JOptionPane.showMessageDialog(
                null, message, "RS-Realm", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // Headless, or no display. The log line above is then the only report, which is better
            // than an exception on top of the failure it was trying to describe.
        }
    }
}
