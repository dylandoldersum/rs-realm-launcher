package rs.realm.launcher;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

/**
 * The remote version manifest. A tiny key=value document (parsed by {@link Properties}) so there are
 * no third-party JSON deps and a malformed field can never crash the launcher.
 */
public record Manifest(String version, String url, String sha256, String changelog) {

    /** Fetches + parses the manifest at {@code manifestUrl}. Throws on network / HTTP errors. */
    public static Manifest fetch(String manifestUrl) throws IOException, InterruptedException {
        HttpClient client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Cache-buster: raw.githubusercontent (Fastly CDN) can serve a stale copy for a few minutes
        // even with no-cache, and different edges can disagree. A unique query param forces a cache
        // miss so version/changelog edits are picked up immediately.
        String busted =
            manifestUrl + (manifestUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(busted))
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", Config.BRAND + "-Launcher")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Manifest returned HTTP " + response.statusCode());
        }

        Properties props = new Properties();
        props.load(new StringReader(response.body()));

        String version = props.getProperty("version", "").trim();
        String url = props.getProperty("url", "").trim();
        if (version.isEmpty() || url.isEmpty()) {
            throw new IOException("Manifest is missing 'version' or 'url'.");
        }
        return new Manifest(
            version,
            url,
            props.getProperty("sha256", "").trim(),
            props.getProperty("changelog", "").trim());
    }
}
