package rs.realm.launcher;

import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Discord half of signing in: open a browser, catch the redirect, hand the code to our backend.
 *
 * <h2>Why PKCE</h2>
 *
 * The redirect lands on {@code http://127.0.0.1:47812/callback} — a loopback address that any other
 * process on the player's machine could, in principle, be listening on. So the authorization code
 * alone is not proof of anything. PKCE fixes that: we invent a random {@code verifier}, send only
 * its SHA-256 hash when opening the browser, and reveal the verifier only when redeeming the code.
 * Whoever intercepts the redirect has the code but not the verifier, and Discord refuses the
 * exchange without it.
 *
 * <h2>Why a fixed port</h2>
 *
 * Discord compares the redirect URI literally and has no loopback-port exemption, so the port must
 * be the one registered in the Discord application. A launcher that grabbed a free port would fail
 * every login with {@code invalid_grant}. The trade-off is that a second launcher instance cannot
 * sign in while the first is mid-login, which is why {@link #awaitCode} reports the bind failure
 * plainly instead of hanging.
 *
 * <p>The client SECRET is never here. This launcher is a zip file anyone can open; the secret stays
 * on the backend, which is the only party that ever completes the exchange.
 */
public final class DiscordLogin {

    /** What a completed browser round-trip yields, ready to hand to the backend. */
    public record Result(String code, String codeVerifier, String redirectUri) {}

    private DiscordLogin() {}

    /**
     * Opens the browser and blocks until Discord redirects back, or the player gives up.
     *
     * @throws IOException if the loopback listener cannot bind, the browser cannot be opened, or no
     *     redirect arrives within the timeout.
     */
    public static Result awaitCode(String clientId, String redirectUri) throws IOException {
        String verifier = randomUrlSafe(64);
        String challenge = sha256Base64Url(verifier);
        String state = randomUrlSafe(24);

        int port = URI.create(redirectUri).getPort();
        String path = URI.create(redirectUri).getPath();
        ArrayBlockingQueue<Object> result = new ArrayBlockingQueue<>(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(path, exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String code = param(query, "code");
            String returnedState = param(query, "state");
            String message;
            if (code == null) {
                message = "Sign-in was cancelled. You can close this tab.";
                result.offer(new IOException("Discord did not return a code."));
            } else if (!state.equals(returnedState)) {
                // Someone else's redirect landed here. Refuse it rather than trade it for a session.
                message = "Sign-in could not be verified. You can close this tab.";
                result.offer(new IOException("State mismatch on the Discord redirect."));
            } else {
                message = "Signed in. You can close this tab and return to the launcher.";
                result.offer(code);
            }
            byte[] body = page(message).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(null);
        server.start();

        try {
            openBrowser(authorizeUrl(clientId, redirectUri, challenge, state));
            Object outcome = result.poll(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (outcome == null) {
                throw new IOException("Timed out waiting for Discord.");
            }
            if (outcome instanceof IOException failure) {
                throw failure;
            }
            return new Result((String) outcome, verifier, redirectUri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sign-in was interrupted.", e);
        } finally {
            // No delay: the browser already has its response, and leaving the port held would block
            // the next attempt after a failed one.
            server.stop(0);
        }
    }

    private static String authorizeUrl(
            String clientId, String redirectUri, String challenge, String state) {
        return "https://discord.com/oauth2/authorize"
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                // `identify` and nothing else. We need a user id; we have no business reading their
                // email, their servers or anything they would have to think twice about granting.
                + "&scope=identify"
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256";
    }

    private static void openBrowser(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
            return;
        }
        // Headless-ish desktops (some Linux setups) have no Desktop support. Fall back to the
        // platform opener rather than telling the player their launcher is broken.
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] command;
        if (os.contains("win")) {
            command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        } else if (os.contains("mac")) {
            command = new String[] {"open", url};
        } else {
            command = new String[] {"xdg-open", url};
        }
        new ProcessBuilder(command).start();
    }

    private static String page(String message) {
        return "<!doctype html><meta charset=utf-8><title>RS-Realm</title>"
                + "<body style=\"background:#22201E;color:#ECE9E3;font:16px sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
                + "<p>" + message + "</p></body>";
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && pair.substring(0, split).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is missing from this JRE", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Long enough to find the right Discord account and read the consent screen, not all day. */
    private static final int TIMEOUT_MINUTES = 5;
}
