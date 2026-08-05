package rs.realm.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the launcher asks the RS-Realm backend.
 *
 * One class, because every call shares the same three concerns: the base URL, the bearer token, and
 * turning a non-2xx reply into a message a player can act on. The server sends {@code {"error": …}}
 * on failure, so {@link #send} surfaces that text rather than a status code — "that name is taken"
 * beats "HTTP 409".
 */
public final class Backend {

    /** A profile the player can play as. */
    public record Profile(int accountId, String loginUsername, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Who the launcher is signed in as. */
    public record Account(String discordId, String username, String avatarUrl) {}

    /** One post from the announcements channel. */
    public record News(String title, String body, String timestamp, String imageUrl) {}

    /** What the backend says about starting a Discord login. */
    public record OAuthConfig(String clientId, String redirectUri, boolean enabled) {}

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String baseUrl;
    private String session;

    public Backend(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Sets the bearer token used for every {@code /auth/} call. Null signs out locally. */
    public void setSession(String session) {
        this.session = session;
    }

    public boolean hasSession() {
        return session != null && !session.isBlank();
    }

    // ------------------------------------------------------------------- public API ----

    public OAuthConfig oauthConfig() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/config", null);
        return new OAuthConfig(
                Json.str(body, "discord_client_id", ""),
                Json.str(body, "redirect_uri", ""),
                Json.bool(body, "oauth_enabled", false));
    }

    public List<News> news() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/news", null);
        List<News> out = new ArrayList<>();
        for (Map<String, Object> item : Json.objects(body, "news")) {
            out.add(
                    new News(
                            Json.str(item, "title", ""),
                            Json.str(item, "body", ""),
                            Json.str(item, "timestamp", ""),
                            Json.str(item, "image_url", null)));
        }
        return out;
    }

    /** Trades a completed Discord round-trip for a launcher session, and remembers it. */
    public Account signIn(DiscordLogin.Result login) throws IOException {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("code", login.code());
        request.put("redirect_uri", login.redirectUri());
        request.put("code_verifier", login.codeVerifier());

        Map<String, Object> body = send("POST", "/auth/discord/exchange", Json.write(request));
        session = Json.str(body, "session", null);
        if (session == null) {
            throw new IOException("The server did not return a session.");
        }
        return account(Json.object(body, "discord"));
    }

    public List<Profile> profiles() throws IOException {
        Map<String, Object> body = send("GET", "/auth/profiles", null);
        return profilesOf(body);
    }

    /** Profiles plus the signed-in account, in one call — what the window needs on open. */
    public Map.Entry<Account, List<Profile>> profilesAndAccount() throws IOException {
        Map<String, Object> body = send("GET", "/auth/profiles", null);
        return Map.entry(account(Json.object(body, "discord")), profilesOf(body));
    }

    public Profile createProfile(String displayName) throws IOException {
        Map<String, Object> body =
                send("POST", "/auth/profiles", Json.write(Map.of("display_name", displayName)));
        return new Profile(
                Json.number(body, "account_id", 0),
                Json.str(body, "login_username", ""),
                Json.str(body, "display_name", displayName));
    }

    /** Mints the one-shot token that authenticates the client. Valid for ~30 seconds. */
    public String launchToken(Profile profile) throws IOException {
        Map<String, Object> body =
                send(
                        "POST",
                        "/auth/launch-token",
                        Json.write(Map.of("login_username", profile.loginUsername())));
        String token = Json.str(body, "token", null);
        if (token == null) {
            throw new IOException("The server did not return a launch token.");
        }
        return token;
    }

    public void signOut() {
        if (hasSession()) {
            // Best effort. A failure here only means the server keeps a row that will expire anyway;
            // what matters to the player is that this machine forgets the token, which we do next.
            try {
                send("POST", "/auth/logout", "{}");
            } catch (IOException ignored) {
                // Nothing useful to say — we are signing out either way.
            }
        }
        session = null;
    }

    // --------------------------------------------------------------------- plumbing ----

    private Account account(Map<String, Object> discord) {
        return new Account(
                Json.str(discord, "id", ""),
                Json.str(discord, "username", "Player"),
                Json.str(discord, "avatar_url", null));
    }

    private List<Profile> profilesOf(Map<String, Object> body) {
        List<Profile> out = new ArrayList<>();
        for (Map<String, Object> item : Json.objects(body, "profiles")) {
            out.add(
                    new Profile(
                            Json.number(item, "account_id", 0),
                            Json.str(item, "login_username", ""),
                            Json.str(item, "display_name", "")));
        }
        return out;
    }

    private Map<String, Object> send(String method, String path, String body) throws IOException {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15));
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        if (hasSession()) {
            builder.header("Authorization", "Bearer " + session);
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted talking to the server.", e);
        }

        Map<String, Object> parsed = Json.parseObject(response.body());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parsed;
        }
        if (status == 401) {
            // The session expired or was revoked. Drop it so the window falls back to the sign-in
            // screen instead of retrying a token that will never work again.
            session = null;
        }
        throw new IOException(Json.str(parsed, "error", "The server returned " + status + "."));
    }
}
