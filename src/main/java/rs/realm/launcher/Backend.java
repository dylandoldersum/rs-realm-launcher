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
    public record Profile(int accountId, String loginUsername, String displayName, Stats stats, Donor donor) {
        public Profile(int accountId, String loginUsername, String displayName) {
            this(accountId, loginUsername, displayName, null, null);
        }

        public Profile(int accountId, String loginUsername, String displayName, Stats stats) {
            this(accountId, loginUsername, displayName, stats, null);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * What a character has donated: [amount] formatted ("€25.00"), and the donator rank it earned
     * ([rank] null below the first) in its colour (0xRRGGBB).
     */
    public record Donor(int cents, String amount, String rank, int colour) {}

    /** A character's numbers as the game last saw them (the showcase); null before its first session. */
    public record Stats(int combatLevel, int totalLevel, String className, int playtimeMinutes) {}

    /** One product of the store. [objId] -1 = a store-points package. */
    public record StoreItem(String id, String name, int objId, int count, String price, String minRank, String description, int useAnim,
                            String category, String render, String was, int percentOff) {
        /** On sale: [was] is the price before (struck through), [percentOff] how much is off. */
        public boolean onSale() {
            return was != null && !was.isEmpty();
        }

        public boolean isPoints() {
            return objId < 0;
        }
    }

    /** The store: its products, and whether buying works yet (payments on, or a staff test purchase). */
    public record Store(List<StoreItem> items, boolean payments, boolean testPurchases, boolean admin) {
        public Store(List<StoreItem> items, boolean payments, boolean testPurchases) {
            this(items, payments, testPurchases, false);
        }

        public boolean canBuy() {
            return payments || testPurchases;
        }
    }

    /** What a purchase started: a checkout page to open (or null), and a line for the player. */
    public record Purchase(String checkoutUrl, String sessionId, String message) {}

    /** Who the launcher is signed in as. */
    public record Account(String discordId, String username, String avatarUrl) {}

    /** One post from the announcements channel. */
    public record News(
            String title, String body, String timestamp, String imageUrl, String author) {}

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

    /** The current bearer token, so the caller can persist it. Null when signed out. */
    public String session() {
        return session;
    }

    // ------------------------------------------------------------------- public API ----

    public OAuthConfig oauthConfig() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/config", null);
        return new OAuthConfig(
                Json.str(body, "discord_client_id", ""),
                Json.str(body, "redirect_uri", ""),
                Json.bool(body, "oauth_enabled", false));
    }

    /** How many players are online right now. */
    public int onlinePlayers() throws IOException {
        return status().players();
    }

    /**
     * The game server's state: how many play, and whether it restarts for an update soon
     * ([ServerStatus#rebootSeconds] -1: no). Throws when the server can't be reached (it is down,
     * as it is for a minute or two while an update installs).
     */
    public ServerStatus status() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/status", null);
        if (!body.containsKey("players")) {
            throw new IOException("The server is not answering.");
        }
        return new ServerStatus(Json.number(body, "players", 0), Json.bool(body, "updating", false) ? 0 : Json.number(body, "reboot_seconds", -1));
    }

    public record ServerStatus(int players, int rebootSeconds) {}

    public List<News> news() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/news", null);
        List<News> out = new ArrayList<>();
        for (Map<String, Object> item : Json.objects(body, "news")) {
            out.add(
                    new News(
                            Json.str(item, "title", ""),
                            Json.str(item, "body", ""),
                            Json.str(item, "timestamp", ""),
                            Json.str(item, "image_url", null),
                            Json.str(item, "author", "")));
        }
        return out;
    }

    /** The sidebar art's url, when one is set on the admin website (else null: the built-in one). */
    public String sidebarArt() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/art", null);
        return Json.str(body, "sidebar", null);
    }

    /** The featured update (the Home tab's banner, set on the admin website), or null when there is none. */
    public News featured() throws IOException {
        Map<String, Object> body = send("GET", "/launcher/featured", null);
        Object item = body.get("featured");
        if (!(item instanceof Map<?, ?>)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) item;
        return new News(
                Json.str(post, "title", ""),
                Json.str(post, "body", ""),
                Json.str(post, "timestamp", ""),
                Json.str(post, "image_url", null),
                Json.str(post, "author", ""));
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

    /**
     * Takes a profile off this Discord account's list.
     *
     * Unlinks rather than deletes — the character and everything on it stay, but nothing points at
     * it any more.
     */
    public void unlinkProfile(Profile profile) throws IOException {
        send(
                "POST",
                "/auth/profiles/unlink",
                Json.write(Map.of("login_username", profile.loginUsername())));
    }

    /**
     * Mints the one-shot token for the caller's character LOBBY: no character is named, so the
     * server logs the client in as this Discord user's lobby account, where characters are chosen
     * and created in game.
     */
    public LobbyToken lobbyToken() throws IOException {
        Map<String, Object> body = send("POST", "/auth/launch-token", "{}");
        String token = Json.str(body, "token", null);
        if (token == null) {
            throw new IOException("The server did not return a launch token.");
        }
        return new LobbyToken(token, Json.number(body, "account_id", 0));
    }

    /**
     * A lobby launch token plus the lobby account's id, which the client gets as JX_CHARACTER_ID:
     * account mode needs a real character id to world-hop (Enter world in the lobby is a hop).
     */
    public record LobbyToken(String token, int accountId) {}

    /** Mints the one-shot token that authenticates the client. */
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

    /**
     * A short code the player types in game as {@code ::discord <code>} to adopt an existing
     * character.
     *
     * No account is named: this end only says "whoever types this is me". Which character it
     * attaches to is decided by whichever one is logged in when the code is spent.
     */
    public String linkCode() throws IOException {
        Map<String, Object> body = send("POST", "/auth/link-code", "{}");
        String code = Json.str(body, "code", null);
        if (code == null) {
            throw new IOException("The server did not return a code.");
        }
        return code;
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

    @SuppressWarnings("unchecked")
    private static Donor donorOf(Map<String, Object> profile) {
        int cents = Json.number(profile, "donated_cents", 0);
        String rank = null;
        int colour = 0xd8cfbd;
        if (profile.get("rank") instanceof Map) {
            Map<String, Object> r = (Map<String, Object>) profile.get("rank");
            rank = Json.str(r, "name", null);
            try {
                colour = Integer.parseInt(Json.str(r, "colour", "d8cfbd"), 16);
            } catch (NumberFormatException ignored) {
                // the default
            }
        }
        return new Donor(cents, Json.str(profile, "donated", ""), rank, colour);
    }

    @SuppressWarnings("unchecked")
    private static Stats statsOf(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<String, Object> s = (Map<String, Object>) value;
        String cls = Json.str(s, "class", "");
        return new Stats(
                Json.number(s, "combat_level", 3),
                Json.number(s, "total_level", 32),
                cls.isEmpty() ? null : cls,
                Json.number(s, "playtime_minutes", 0));
    }

    public Store store() throws IOException {
        Map<String, Object> body = send("GET", "/auth/store", null);
        List<StoreItem> items = new ArrayList<>();
        for (Map<String, Object> item : Json.objects(body, "items")) {
            String rank = Json.str(item, "min_rank", "");
            items.add(new StoreItem(
                    Json.str(item, "id", ""),
                    Json.str(item, "name", ""),
                    Json.number(item, "obj_id", -1),
                    Json.number(item, "count", 1),
                    Json.str(item, "price", ""),
                    rank.isEmpty() ? null : rank,
                    Json.str(item, "description", ""),
                    Json.number(item, "use_anim", -1),
                    Json.str(item, "category", "Items"),
                    Json.str(item, "render", ""),
                    Json.str(item, "was", ""),
                    Json.number(item, "percent_off", 0)));
        }
        return new Store(items, Json.bool(body, "payments", false), Json.bool(body, "test_purchases", false),
                Json.bool(body, "admin", false));
    }

    /** A default store picture as downloaded: the zip, and when the server's copy was made. */
    public record RenderZip(byte[] zip, long time) {}

    /**
     * The default picture of store item [objId] ("item", "worn" or "use"), or null when the server
     * has none or none newer than [since].
     */
    public RenderZip downloadRender(int objId, String kind, long since) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/launcher/renders/" + objId + "/" + kind + "?since=" + since))
                .timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            long time = response.headers().firstValue("X-Render-Time").map(Long::parseLong).orElse(System.currentTimeMillis());
            return new RenderZip(response.body(), time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    /** Publishes a store picture as everyone's default (admins only). */
    public void uploadRender(int objId, String kind, byte[] zip) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/admin/renders/" + objId + "/" + kind))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(zip));
        if (hasSession()) {
            builder.header("Authorization", "Bearer " + session);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(Json.str(Json.parseObject(response.body()), "error", "upload failed"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    /** Buys [item] for [profile]: see {@link Purchase}. */
    public Purchase buy(StoreItem item, Profile profile) throws IOException {
        Map<String, Object> body = send("POST", "/auth/store/buy",
                Json.write(Map.of("product_id", item.id(), "account_id", Integer.toString(profile.accountId()))));
        String url = Json.str(body, "checkout_url", "");
        String sessionId = Json.str(body, "session_id", "");
        return new Purchase(url.isEmpty() ? null : url, sessionId.isEmpty() ? null : sessionId, Json.str(body, "message", "Done."));
    }

    /** How a purchase is doing: "pending" (not paid), "paid" (on its way) or "claimed" (delivered). */
    public String purchaseStatus(String sessionId) throws IOException {
        Map<String, Object> body = send("GET", "/auth/store/status?session=" + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8), null);
        return Json.str(body, "status", "pending");
    }

    private List<Profile> profilesOf(Map<String, Object> body) {
        List<Profile> out = new ArrayList<>();
        for (Map<String, Object> item : Json.objects(body, "profiles")) {
            out.add(
                    new Profile(
                            Json.number(item, "account_id", 0),
                            Json.str(item, "login_username", ""),
                            Json.str(item, "display_name", ""),
                            statsOf(item.get("stats")),
                            donorOf(item)));
        }
        return out;
    }

    /** A 403. Separate from a plain failure so a caller can treat "not you" as a normal answer. */
    public static final class NotAllowedException extends IOException {
        NotAllowedException(String message) {
            super(message);
        }
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
        if (status == 403) {
            throw new NotAllowedException(Json.str(parsed, "error", "not allowed"));
        }
        if (status == 401) {
            // The session expired or was revoked. Drop it so the window falls back to the sign-in
            // screen instead of retrying a token that will never work again.
            session = null;
        }
        throw new IOException(Json.str(parsed, "error", "The server returned " + status + "."));
    }
}
