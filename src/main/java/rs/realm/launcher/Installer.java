package rs.realm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

/** Downloads the client jar, verifies it, and records the installed version. */
public final class Installer {
    private Installer() {}

    /** Reports download progress (0..1, or -1 when the total size is unknown). */
    public interface Progress {
        void update(double fraction, long bytesRead, long totalBytes);
    }

    /** The version currently installed on disk, or {@code null} if nothing is installed. */
    public static String installedVersion() {
        if (!Files.exists(Config.CLIENT_JAR) || !Files.exists(Config.INSTALLED_FILE)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(Config.INSTALLED_FILE)) {
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "").trim();
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Downloads {@code manifest.url} to the install dir, verifies its sha256 (when the manifest
     * provides one), atomically swaps it into place, and records the installed version.
     */
    public static void install(Manifest manifest, Progress progress)
        throws IOException, InterruptedException {
        Files.createDirectories(Config.INSTALL_DIR);
        Path temp = Config.INSTALL_DIR.resolve("fiddled-client.jar.download");

        HttpClient client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(manifest.url()))
                .header("User-Agent", Config.BRAND + "-Launcher")
                .GET()
                .build();

        HttpResponse<InputStream> response =
            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download returned HTTP " + response.statusCode());
        }

        long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }

        long read = 0;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = response.body();
                OutputStream out = Files.newOutputStream(temp)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                sha.update(buffer, 0, n);
                read += n;
                double fraction = total > 0 ? (double) read / total : -1;
                progress.update(fraction, read, total);
            }
        }

        String expected = manifest.sha256();
        if (!expected.isEmpty()) {
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(temp);
                throw new IOException("Downloaded file failed the integrity check (sha256 mismatch).");
            }
        }

        Files.move(
            temp,
            Config.CLIENT_JAR,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        writeInstalledVersion(manifest.version());
    }

    private static void writeInstalledVersion(String version) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", version);
        try (OutputStream out = Files.newOutputStream(Config.INSTALLED_FILE)) {
            p.store(out, "RS-Realm launcher — installed client version");
        }
    }
}
